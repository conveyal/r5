package com.conveyal.r5.analyst.cluster;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.conveyal.r5.analyst.LittleEndianIntOutputStream;
import com.google.common.io.ByteStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.r5.common.Util.human;

/**
 * Assemble the results of GridComputer into AccessGrids.
 *
 * DEPRECATED because this has been copied into analysis-backend where it belongs.
 */
@Deprecated
public class GridResultAssembler {

    public static final Logger LOG = LoggerFactory.getLogger(GridResultAssembler.class);

    /** The version of the access grids we produce */
    public static final int ACCESS_GRID_VERSION = 0;

    /** The offset to get to the data section of the access grid file. */
    public static final long HEADER_LENGTH_BYTES = 9 * Integer.BYTES;

    private static final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();

    public final AnalysisTask request;

    private File bufferFile;

    private RandomAccessFile randomAccessFile;

    /** TODO use Java NewIO file channel for native byte order output to our output file. */
//    private FileChannel outputFileChannel;

    private boolean error = false;

    /**
     * The number of results received for unique origin points (i.e. two results for the same origin should only
     * increment this once). It does not need to be an atomic int as it's incremented in a synchronized block.
     */
    public int nComplete = 0;

    // We need to keep track of which specific origins are completed, to avoid double counting if we receive more than
    // one result for the same origin.
    private BitSet originsReceived;

    /** Total number of results expected. */
    public int nTotal;

    /** The bucket on S3 to which the final result will be written. */
    public final String outputBucket;

    /**
     * Construct an assembler for a single regional analysis result grid.
     * This also creates the on-disk scratch buffer into which the results from the workers will be accumulated.
     */
    public GridResultAssembler (AnalysisTask request, String outputBucket) {
        this.request = request;
        this.outputBucket = outputBucket;
        nTotal = request.width * request.height;
        originsReceived = new BitSet(nTotal);
        LOG.info("Expecting results for regional analysis with width {}, height {}, 1 value per origin.",
                request.width, request.height);

        long outputFileSizeBytes = request.width * request.height * Integer.BYTES;
        LOG.info("Creating temporary file to store regional analysis results, size is {}.",
                human(outputFileSizeBytes, "B"));
        try {
            bufferFile = File.createTempFile(request.jobId, ".access_grid");
            // On unexpected server shutdown, these files should be deleted.
            // We could attempt to recover from shutdowns but that will take a lot of changes and persisted data.
            bufferFile.deleteOnExit();

            // Write the access grid file header
            FileOutputStream fos = new FileOutputStream(bufferFile);
            LittleEndianIntOutputStream data = new LittleEndianIntOutputStream(fos);
            data.writeAscii("ACCESSGR");
            data.writeInt(ACCESS_GRID_VERSION);
            data.writeInt(request.zoom);
            data.writeInt(request.west);
            data.writeInt(request.north);
            data.writeInt(request.width);
            data.writeInt(request.height);
            data.writeInt(1); // Hard-wired to one bootstrap replication TODO one value per cutoff
            data.close();

            // We used to fill the file with zeros here, to "overwrite anything that might be in the file already"
            // according to a code comment. However that creates a burst of up to 1GB of disk activity, which exhausts
            // our IOPS budget on cloud servers with network storage. That then causes the server to fall behind in
            // processing incoming results.
            // This is a newly created temp file, so setting it to a larger size should just create a sparse file
            // full of blocks of zeros (at least on Linux, I don't know what it does on Windows).
            // TODO FileChannel / NIO?
            this.randomAccessFile = new RandomAccessFile(bufferFile, "rw");
            randomAccessFile.setLength(outputFileSizeBytes);
            LOG.info("Created temporary file of {} to accumulate results from workers.", human(randomAccessFile.length(), "B"));
        } catch (Exception e) {
            error = true;
            LOG.error("Exception while creating regional access grid: " + e.toString());
        }
    }

    /**
     * Gzip the access grid and upload it to S3.
     */
    protected synchronized void finish () {
        LOG.info("Finished receiving data for regional analysis {}, uploading to S3", request.jobId);
        try {
            File gzippedGridFile = File.createTempFile(request.jobId, ".access_grid.gz");
            randomAccessFile.close();

            // There's probably a more elegant way to do this with NIO and without closing the buffer.
            // That would be Files.copy or ByteStreams.copy.
            InputStream is = new BufferedInputStream(new FileInputStream(bufferFile));
            OutputStream os = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(gzippedGridFile)));
            ByteStreams.copy(is, os);
            is.close();
            os.close();

            LOG.info("GZIP compression reduced regional analysis {} from {} to {} ({}x compression)",
                    request.jobId,
                    human(bufferFile.length(), "B"),
                    human(gzippedGridFile.length(), "B"),
                    (double) bufferFile.length() / gzippedGridFile.length()
            );
            // TODO use generic filePersistence instead of specific S3 client
            s3.putObject(outputBucket, String.format("%s.access", request.jobId), gzippedGridFile);
            // Clear temporary files off of the disk because the gzipped version is now on S3.
            bufferFile.delete();
            gzippedGridFile.delete();
        } catch (Exception e) {
            LOG.error("Error uploading results of regional analysis {}", request.jobId, e);
        }
    }

    private void checkDimension (RegionalWorkResult workResult, String dimensionName, int seen, int expected) {
        if (seen != expected) {
            LOG.error("Result for task {} of job {} has {} {}, expected {}.",
                    workResult.taskId, workResult.jobId, dimensionName, seen, expected);
            error = true;
        }
    }

    // FIXME can't we use some kind of little-endian NIO buffer?
    public static byte[] intToLittleEndianByteArray (int i) {
        final ByteBuffer byteBuffer = ByteBuffer.allocate(Integer.BYTES);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.putInt(i);
        return byteBuffer.array();
    }

    // Write to the proper subregion of the buffer for this origin.
    // The randomAccessFile is not threadsafe and multiple threads may call this, so synchronize.
    // The origins we receive have 2d coordinates.
    // Flatten them to compute file offsets and for the origin checklist.
    // TODO add parameter for cutoff number, or write an array of values for all cutoff numbers at once
    private void writeOneValue (int x, int y, int value) throws IOException {
        int index1d = y * request.width + x;
        long offset = HEADER_LENGTH_BYTES + index1d * Integer.BYTES;
        synchronized (this) {
            randomAccessFile.seek(offset);
            randomAccessFile.write(intToLittleEndianByteArray(value));
            // Don't double-count origins if we receive them more than once.
            if (!originsReceived.get(index1d)) {
                originsReceived.set(index1d);
                nComplete += 1;
            }
        }
    }

    /**
     * Process a single result.
     * We have bootstrap replications turned off, so there should be only one accessibility result per origin
     * and no delta coding is necessary anymore within each origin.
     * We are also iterating over three dimensions (grids, percentiles, cutoffs) but those should produce completely
     * separate access grid files, and are all size 1 for now anyway.
     */
    public void handleMessage (RegionalWorkResult workResult) {
        try {
            // Infer x and y cell indexes based on the template task
            int taskNumber = workResult.taskId;
            int x = taskNumber % request.width;
            int y = taskNumber / request.width;

            // Check the dimensions of the result by comparing with fields of this.request
            int nGrids = 1;
            int nPercentiles = 1;
            int nCutoffs = 1;

            // Drop work results for this particular origin into a little-endian output files.
            // We only have one file for now because only one grid, percentile, and cutoff value.
            checkDimension(workResult, "destination grids", workResult.accessibilityValues.length, nGrids);
            for (int[][] gridResult : workResult.accessibilityValues) {
                checkDimension(workResult, "percentiles", gridResult.length, nPercentiles);
                for (int[] percentileResult : gridResult) {
                    checkDimension(workResult, "cutoffs", percentileResult.length, nCutoffs);
                    for (int accessibilityForCutoff : percentileResult) {
                        writeOneValue(x, y, accessibilityForCutoff);
                    }
                }
            }
            // TODO It might be more reliable to double-check the bitset of received results inside finish() instead of just counting.
            // FIXME isn't this leaving the files around and the assemblers in memory if the job errors out?
            if (nComplete == nTotal && !error) finish();
        } catch (Exception e) {
            error = true; // the file is garbage TODO better resilience, tell the UI, transmit all errors.
            LOG.error("Error assembling results for query {}", request.jobId, e);
            return;
        }
    }

    /** Clean up and cancel a consumer. */
    public synchronized void terminate () throws IOException {
        this.randomAccessFile.close();
        bufferFile.delete();
    }

}
