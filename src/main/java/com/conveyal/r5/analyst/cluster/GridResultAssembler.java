package com.conveyal.r5.analyst.cluster;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
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
import java.util.BitSet;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.r5.common.Util.human;

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

    private static final int INT32_WIDTH_BYTES = 4;

    /** The version of the access grids we produce */
    public static final int VERSION = 0;

    /** The version of the origins we consume */
    public static final int ORIGIN_VERSION = 0;

    /** The offset to get to the data section of the access grid file */
    public static final long DATA_OFFSET = 9 * 4;

    private static final AmazonS3 s3 = new AmazonS3Client();

    private Base64.Decoder base64 = Base64.getDecoder();

    public final AnalysisTask request;

    private File temporaryFile;
    private RandomAccessFile bufferFile;

    private boolean error = false;

    /**
     * The number of results received for unique origin points (i.e. two results for the same origin should only
     * increment this once). It does not need to be an atomic int as it's incremented in a synchronized block.
     */
    public int nComplete = 0;

    // We need to keep track of which specific origins are completed, to avoid double counting if we receive more than
    // one result for the same origin.
    private BitSet originsReceived;

    public int nTotal;

    public final String outputBucket;

    /** Number of iterations for this grid task */
    private int nIterations;

    public GridResultAssembler (AnalysisTask request, String outputBucket) {
        this.request = request;
        this.outputBucket = outputBucket;
        nTotal = request.width * request.height;
        originsReceived = new BitSet(nTotal);
    }

    protected synchronized void finish () {
        // gzip and push up to S3
        LOG.info("Finished receiving data for regional analysis {}, uploading to S3", request.jobId);

        try {
            File gzipFile = File.createTempFile(request.jobId, ".access_grid.gz");

            bufferFile.close();

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

            // if this is not the first task, make sure that we have the correct number of accessibility
            // samples (either instantaneous accessibility values or bootstrap replications of accessibility given median
            // travel time, depending on worker version)
            if (bufferFile != null && origin.samples.length != this.nIterations) {
                LOG.error("Origin {}, {} has {} samples, expected {}",
                        origin.x, origin.y, origin.samples.length, this.nIterations);
                error = true;
            }

            // Convert to a delta coded byte array
            ByteArrayOutputStream pixelByteOutputStream = new ByteArrayOutputStream(origin.samples.length * INT32_WIDTH_BYTES);
            LittleEndianDataOutputStream pixelDataOutputStream = new LittleEndianDataOutputStream(pixelByteOutputStream);
            for (int i = 0, prev = 0; i < origin.samples.length; i++) {
                int current = origin.samples[i];
                pixelDataOutputStream.writeInt(current - prev);
                prev = current;
            }
            pixelDataOutputStream.close();

            // write to the proper subregion of the buffer for this origin
            // use a synchronized block to ensure no threading issues
            synchronized (this) {
                if (bufferFile == null) this.initialize(origin.samples.length);
                // The origins we receive have 2d coordinates.
                // Flatten them to compute file offsets and for the origin checklist.
                // The 1D index should not overflow an int (the grid would need to be 1000x bigger than the Netherlands)
                // However the output file size can easily exceed 2^31, and intermediate results in the file offset
                // computation are susceptible to overflow (see #321).
                // Casting the 1D offset to long should ensure that each intermediate result is stored in a long.
                int index1d = origin.y * request.width + origin.x;
                long offset = DATA_OFFSET + ((long)index1d) * nIterations * INT32_WIDTH_BYTES;
                bufferFile.seek(offset);
                bufferFile.write(pixelByteOutputStream.toByteArray());
                // Don't double-count origins if we receive them more than once.
                if (!originsReceived.get(index1d)) {
                    originsReceived.set(index1d);
                    nComplete += 1;
                }
                // TODO It might be more reliable to double-check the bitset of received results inside finish() instead of just counting.
                if (nComplete == nTotal && !error) finish();
            }
        } catch (Exception e) {
            error = true; // the file is garbage TODO better resilience
            LOG.error("Error assembling results for query {}", request.jobId, e);
            return;
        }
    }

    public synchronized void initialize (int nIterations) throws IOException {

        long finalFileSizeBytes = (long) nIterations * (long) request.width * (long) request.height * INT32_WIDTH_BYTES;

        LOG.info("Expecting results for regional analysis with width {}, height {}, iterations {}.", request.width, request.height, nIterations);
        LOG.info("Creating temporary file to store regional analysis results, size is {}.", human(finalFileSizeBytes, "B"));

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
        data.close();

        // We used to fill the file with zeros here, to "overwrite anything that might be in the file already"
        // according to a code comment. However that creates a burst of up to 1GB of disk activity, which exhausts
        // our IOPS budget on cloud servers with network storage. That then causes the server to fall behind in
        // processing incoming results.
        // This is a newly created temp file, so setting it to a larger size should just create a sparse file
        // full of blocks of zeros (at least on Linux, I don't know what it does on Windows).

        this.bufferFile = new RandomAccessFile(temporaryFile, "rw");
        bufferFile.setLength(finalFileSizeBytes);

        LOG.info("Created temporary file of {} to store query results", human(bufferFile.length(), "B"));

    }

    /** Clean up and cancel a consumer */
    public synchronized void terminate () throws IOException {
        this.bufferFile.close();
        temporaryFile.delete();
    }
}
