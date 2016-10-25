package com.conveyal.r5.analyst.cluster;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.Message;
import com.google.common.io.ByteStreams;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.io.LittleEndianDataOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

/**
 * Assemble the results of GridComputer into AccessGrids.
 *
 * Access grids look like this:
 * Header (ASCII text "GRID") (note that the header is four bytes, so the full grid can be mapped into a Javascript
 *   typed array if desired)
 * Version, 4-byte integer
 * (4 byte int) Web mercator zoom level
 * (4 byte int) west (x) edge of the grid, i.e. how many pixels this grid is east of the left edge of the world
 * (4 byte int) north (y) edge of the grid, i.e. how many pixels this grid is south of the top edge of the world
 * (4 byte int) width of the grid in pixels
 * (4 byte int) height of the grid in pixels
 * (4 byte int) number of values per pixel
 * (repeated 4-byte int) values of each pixel in row major order. Values within a given pixel are delta coded.
 */
public class GridResultAssembler {
    public static final Logger LOG = LoggerFactory.getLogger(GridResultAssembler.class);

    /** The version of the access grids we produce */
    public static final int VERSION = 0;

    /** The version of the origins we consume */
    public static final int ORIGIN_VERSION = 0;

    /** The offset to get to the data section of the access grid file */
    public static final long DATA_OFFSET = 8 * 4;

    private static final AmazonSQS sqs = new AmazonSQSClient();
    private static final AmazonS3 s3 = new AmazonS3Client();

    private Base64.Decoder base64 = Base64.getDecoder();

    private final GridRequest request;

    private File temporaryFile;
    private RandomAccessFile buffer;

    private boolean error = false;

    /** this does not need to be an atomic int as it's incremented in a synchronized block */
    public int nComplete = 0;

    public int nTotal;

    private final String outputBucket;

    public GridResultAssembler (GridRequest request, String outputBucket) {
        this.request = request;
        this.outputBucket = outputBucket;
        nTotal = request.width * request.height;
    }

    private void finish () {
        // gzip and push up to S3
        LOG.info("Finished receiving data for regional analysis {}, uploading to S3", request.jobId);

        try {
            File gzipFile = File.createTempFile(request.jobId, ".access_grid.gz");

            buffer.close();

            // There's probably a more elegant way to do this with NIO and without closing the buffer
            InputStream is = new BufferedInputStream(new FileInputStream(temporaryFile));
            OutputStream os = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(gzipFile)));
            ByteStreams.copy(is, os);
            is.close();
            os.close();

            LOG.info("GZIP compression reduced regional analysis {} from {}mb to {}mb ({}x compression)",
                    request.jobId,
                    temporaryFile.length() / 1024 / 1024,
                    gzipFile.length() / 1024 / 1024,
                    temporaryFile.length() / gzipFile.length()
                    );

            s3.putObject(outputBucket, String.format("%s.access", request.jobId), gzipFile);

            // these will fill up the disk lickety-split if we don't keep them cleaned up.
            gzipFile.delete();
            temporaryFile.delete();
        } catch (Exception e) {
            LOG.error("Error uploading results of regional analysis {}", request.jobId, e);
        }
    }

    /** Process an SQS message */
    public void handleMessage (Message message) {
        try {
            byte[] body = base64.decode(message.getBody());
            ByteArrayInputStream bais = new ByteArrayInputStream(body);
            LittleEndianDataInputStream data = new LittleEndianDataInputStream(bais);

            // ensure that it starts with ORIGIN
            char[] header = new char[6];
            for (int i = 0; i < 6; i++) {
                header[i] = (char) data.readByte();
            }

            if (!"ORIGIN".equals(new String(header))) {
                error = true;
                LOG.error("Origin not in proper format for query %s");
                return;
            }

            int version = data.readInt();

            if (version != ORIGIN_VERSION) {
                error = true;
                LOG.error("Origin version mismatch for query %s, expected {}, found {}", ORIGIN_VERSION, version);
            }

            int x = data.readInt();
            int y = data.readInt();
            int iterations = data.readInt();

            // convert the remainder to a delta coded byte array
            ByteArrayOutputStream originData = new ByteArrayOutputStream(iterations * 4);
            LittleEndianDataOutputStream origin = new LittleEndianDataOutputStream(originData);
            for (int i = 0, prev = 0; i < iterations; i++) {
                int current = data.readInt();
                origin.writeInt(current - prev);
                prev = current;
            }
            origin.close();

            // write to the buffer in a synchronized block to ensure no threading issues
            synchronized (this) {
                if (buffer == null) this.initialize(iterations);

                long offset = DATA_OFFSET + (y * request.width + x) * 4 * iterations;
                buffer.seek(offset);
                buffer.write(originData.toByteArray());
                if (++nComplete == nTotal && !error) finish();
            }
        } catch (Exception e) {
            error = true; // the file is garbage TODO better resilience
            LOG.error("Error assembling results for query %s", request.jobId, e);
            return;
        }
    }

    public void initialize (int nIterations) throws IOException {
        // create a temporary file an fill it in with relevant data
        temporaryFile = File.createTempFile(request.jobId, ".access_grid");
        // write the header
        FileOutputStream fos = new FileOutputStream(temporaryFile);
        LittleEndianDataOutputStream data = new LittleEndianDataOutputStream(fos);

        for (char c : "GRID".toCharArray()) {
            data.writeByte((byte) c);
        }

        data.writeInt(VERSION);

        data.writeInt(request.zoom);
        data.writeInt(request.west);
        data.writeInt(request.north);
        data.writeInt(request.width);
        data.writeInt(request.height);
        data.writeInt(nIterations);

        // cast to long, file might be bigger than 2GB
        // overwrite anything that might be in the file already
        for (long i = 0; i < (long) nIterations * (long) request.width * (long) request.height; i++) {
            data.writeInt(0);
        }

        data.close();

        LOG.info("Allocated temporary file of {}mb to store query results", temporaryFile.length() / 1024 / 1024);

        this.buffer = new RandomAccessFile(temporaryFile, "rw");
    }
}
