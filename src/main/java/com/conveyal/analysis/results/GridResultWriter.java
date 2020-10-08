package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.LittleEndianIntOutputStream;
import com.conveyal.r5.analyst.cluster.RegionalTask;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.conveyal.r5.common.Util.human;

/**
 * Write regional analysis results arriving from workers into a binary grid format. This how we
 * store accessibility indicators for sets of origin points lying on regular grids.
 * <p>
 * During distributed computation of access to gridded destinations, workers return raw results for
 * single origins to the worker while polling. These results contain one accessibility measurement
 * per origin grid cell. This class assembles these results into a single large file containing a
 * delta-coded version of the same data for all origin points.
 * <p>
 * Access grids look like this:
 * <ol>
 * <li>Header (ASCII text "ACCESSGR", note that this header is eight bytes, so the full grid can be
 *         mapped into a Javascript typed array of 4-byte integers if desired)</li>
 * <li>(4 byte int) File format version</li>
 * <li>(4 byte int) Web mercator zoom level</li>
 * <li>(4 byte int) west (x) edge of the grid, i.e. how many pixels this grid is east of the left edge of the world</li>
 * <li>(4 byte int) north (y) edge of the grid, i.e. how many pixels this grid is south of the top edge of the world</li>
 * <li>(4 byte int) width of the grid in pixels</li>
 * <li>(4 byte int) height of the grid in pixels</li>
 * <li>(4 byte int) number of values (channels) per pixel</li>
 * <li>(repeated 4-byte int) values of each pixel in row-major order: axis order (row, column, channel).</li>
 * </ol>
 */
public class GridResultWriter extends ResultWriter {

    private RandomAccessFile randomAccessFile;

    /** The version of the access grids we produce */
    private static final int ACCESS_GRID_VERSION = 0;

    /** The offset to get to the data section of the access grid file. */
    private static final long HEADER_LENGTH_BYTES = 9 * Integer.BYTES;

    private final int channels;

    /**
     * Construct an writer for a single regional analysis result grid, using the proprietary
     * Conveyal grid format. This also creates the on-disk scratch buffer into which the results
     * from the workers will be accumulated.
     */
    GridResultWriter (RegionalTask task, String outputBucket, FileStorage fileStorage) throws IOException {
        super(fileStorage);
        int width = task.width;
        int height = task.height;
        this.channels = task.cutoffsMinutes.length;
        LOG.info(
            "Expecting multi-origin results for grid with width {}, height {}, {} values per origin.",
            width,
            height,
            channels
        );
        long outputFileSizeBytes = width * height * channels  * Integer.BYTES;
        super.prepare(task.jobId, outputBucket);

        // Write the access grid file header to the temporary file.
        FileOutputStream fos = new FileOutputStream(bufferFile);
        LittleEndianIntOutputStream data = new LittleEndianIntOutputStream(fos);
        data.writeAscii("ACCESSGR");
        data.writeInt(ACCESS_GRID_VERSION);
        data.writeInt(task.zoom);
        data.writeInt(task.west);
        data.writeInt(task.north);
        data.writeInt(width);
        data.writeInt(height);
        data.writeInt(channels);
        data.close();

        // Initialize the temporary file where the accessibility results will be stored. Setting this newly created
        // file to a larger size should just create a sparse file full of blocks of zeros (at least on Linux).
        // In the past we filled the file with zeros here, to "overwrite anything that might be in the file already"
        // according to a code comment. However that creates a burst of up to 1GB of disk activity, which exhausts
        // the IOPS budget on cloud servers with network storage. That then causes the server to fall behind in
        // processing incoming results.
        this.randomAccessFile = new RandomAccessFile(bufferFile, "rw");
        randomAccessFile.setLength(outputFileSizeBytes); //TODO check if this should include header length
        LOG.info("Created temporary file to accumulate results from workers, size is {}.",
                human(randomAccessFile.length(), "B"));
    }

    /** Gzip the access grid and upload it to S3. */
    @Override
    protected synchronized void finish (String fileName) throws IOException {
        super.finish(fileName);
        randomAccessFile.close();
    }

    /**
     * TODO is this inefficient? Would it be reasonable to just store the regional results in memory
     *      in a byte buffer instead of writing mini byte buffers into files? We should also be able
     *      to use a filechannel with native order.
     */
    private static byte[] intToLittleEndianByteArray (int i) {
        final ByteBuffer byteBuffer = ByteBuffer.allocate(Integer.BYTES);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.putInt(i);
        return byteBuffer.array();
    }

    /**
     * Write all channels at once to the proper subregion of the buffer for this origin. The origins we receive have 2d
     * coordinates. Flatten them to compute file offsets and for the origin checklist.
     */
    synchronized void writeOneOrigin (int taskNumber, int[] values) throws IOException {
        if (values.length != channels) {
            throw new IllegalArgumentException("Number of channels to be written does not match this writer.");
        }
        long offset = HEADER_LENGTH_BYTES + (taskNumber * channels * Integer.BYTES);
        // RandomAccessFile is not threadsafe and multiple threads may call this, so synchronize.
        // TODO why is the method also synchronized then?
        synchronized (this) {
            randomAccessFile.seek(offset);
            // FIXME should this be delta-coded? The Selecting grid reducer seems to expect it to be.
            int lastValue = 0;
            for (int value : values) {
                int delta = value - lastValue;
                randomAccessFile.write(intToLittleEndianByteArray(delta));
                lastValue = value;
            }
        }
    }

    @Override
    synchronized void terminate () throws IOException {
        bufferFile.delete();
        randomAccessFile.close();
    }

}
