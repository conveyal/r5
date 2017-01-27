package com.conveyal.r5.analyst.cluster;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.Message;
import com.conveyal.r5.analyst.LittleEndianIntOutputStream;
import com.google.common.io.ByteStreams;
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
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * Assemble the results of GridComputer into AccessGrids.
 *
 * During distributed computation of access to gridded destinations, workers place raw accessibility results for single
 * origins onto an Amazon SQS queue. These results contain one accessibility measurement per iteration (departure
 * minutes and Monte Carlo draws) per origin grid cell. This class pulls results off the queue as they become available,
 * and assembles them into a single large file containing a delta-coded version of the same data for all origin points.
 *
 * Access grids look like this:
 * Header (ASCII text "ACCESSGR") (note that this header is eight bytes, so the full grid can be mapped into a
 *   Javascript typed array if desired)
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
    public static final long DATA_OFFSET = 9 * 4;

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

    /** Number of iterations for this grid request */
    private int nIterations;

    public GridResultAssembler (GridRequest request, String outputBucket) {
        this.request = request;
        this.outputBucket = outputBucket;
        nTotal = request.width * request.height;
    }

    private synchronized void finish () {
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
            Origin origin = Origin.read(bais);

            // if this is not the first request, make sure that we have the correct number of iterations
            if (buffer != null && origin.accessibilityPerIteration.length != this.nIterations) {
                LOG.error("Origin {}, {} has {} iterations, expected {}",
                        origin.x, origin.y, origin.accessibilityPerIteration.length, this.nIterations);
                error = true;
            }

            // convert to a delta coded byte array
            ByteArrayOutputStream pixelByteOutputStream = new ByteArrayOutputStream(origin.accessibilityPerIteration.length * 4);
            LittleEndianDataOutputStream pixelDataOutputStream = new LittleEndianDataOutputStream(pixelByteOutputStream);
            for (int i = 0, prev = 0; i < origin.accessibilityPerIteration.length; i++) {
                int current = origin.accessibilityPerIteration[i];
                pixelDataOutputStream.writeInt(current - prev);
                prev = current;
            }
            pixelDataOutputStream.close();

            // write to the proper subregion of the buffer for this origin
            // use a synchronized block to ensure no threading issues
            synchronized (this) {
                if (buffer == null) this.initialize(origin.accessibilityPerIteration.length);

                long offset = DATA_OFFSET + (origin.y * request.width + origin.x) * 4 * nIterations;
                buffer.seek(offset);
                buffer.write(pixelByteOutputStream.toByteArray());
                if (++nComplete == nTotal && !error) finish();
            }
        } catch (Exception e) {
            error = true; // the file is garbage TODO better resilience
            LOG.error("Error assembling results for query {}", request.jobId, e);
            return;
        }
    }

    public synchronized void initialize (int nIterations) throws IOException {
        this.nIterations = nIterations;

        // create a temporary file an fill it in with relevant data
        temporaryFile = File.createTempFile(request.jobId, ".access_grid");
        temporaryFile.deleteOnExit(); // handle unclean broker shutdown

        // write the header
        FileOutputStream fos = new FileOutputStream(temporaryFile);
        LittleEndianIntOutputStream data = new LittleEndianIntOutputStream(fos);

        data.writeAscii("ACCESSGR");

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

    /** Clean up and cancel a consumer */
    public synchronized void terminate () throws IOException {
        this.buffer.close();
        temporaryFile.delete();
    }
}
