package com.conveyal.analysis.results;

import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.file.FileCategory;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.LittleEndianIntOutputStream;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

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
public class GridResultWriter implements RegionalResultWriter {

    private static final Logger LOG = LoggerFactory.getLogger(GridResultWriter.class);

    private final File bufferFile = FileUtils.createScratchFile("grid");
    private final FileStorage fileStorage;
    private final RandomAccessFile randomAccessFile;

    /** The version of the access grids we produce */
    private static final int ACCESS_GRID_VERSION = 0;

    /** The offset to get to the data section of the access grid file. */
    private static final long HEADER_LENGTH_BYTES = 9 * Integer.BYTES;

    /**
     * The number of different travel time cutoffs being applied when computing accessibility for each origin.
     * The number of values stored per origin cell in an accessibility results grid.
     * Note that we're storing only the number of different cutoffs, but not the cutoff values themselves in the file.
     * This means that the files can only be properly interpreted with the Mongo metadata from the regional analysis.
     * This is an intentional choice to avoid changing the file format, and in any case these files are not expected
     * to ever be used separately from an environment where the Mongo database is available.
     */
    private final int channels;

    private final int percentileIndex;
    private final int destinationIndex;
    private final String gridFileName;

    /**
     * We create one GridResultWriter for each destination pointset and percentile.
     * Each of those output files contains data for all specified travel time cutoffs at each origin.
     */
    public static List<GridResultWriter> createWritersFromTask(RegionalAnalysis regionalAnalysis, RegionalTask task, FileStorage fileStorage) {
        int nPercentiles = task.percentiles.length;
        int nDestinationPointSets = task.makeTauiSite ? 0 : task.destinationPointSetKeys.length;
        // Create one grid writer per percentile and destination pointset.
        var gridWriters = new ArrayList<GridResultWriter>();
        for (int destinationIndex = 0; destinationIndex < nDestinationPointSets; destinationIndex++) {
            for (int percentileIndex = 0; percentileIndex < nPercentiles; percentileIndex++) {
                String destinationPointSetId = regionalAnalysis.destinationPointSetIds[destinationIndex];
                gridWriters.add(new GridResultWriter(
                        task,
                        fileStorage,
                        percentileIndex,
                        destinationIndex,
                        destinationPointSetId
                ));
            }
        }
        return gridWriters;
    }

    /**
     * Construct a writer for a single regional analysis result grid, using the proprietary
     * Conveyal grid format. This also creates the on-disk scratch buffer into which the results
     * from the workers will be accumulated.
     */
    GridResultWriter (RegionalTask task, FileStorage fileStorage, int percentileIndex, int destinationIndex, String destinationPointSetId) {
        this.fileStorage = fileStorage;
        this.gridFileName = String.format("%s_%s_P%d.access", task.jobId, destinationPointSetId, task.percentiles[percentileIndex]);
        this.percentileIndex = percentileIndex;
        this.destinationIndex = destinationIndex;
        int width = task.width;
        int height = task.height;
        this.channels = task.cutoffsMinutes.length;
        LOG.info(
            "Expecting multi-origin results for grid with width {}, height {}, {} values per origin.",
            width,
            height,
            channels
        );

        try {
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
            // The call to setLength is not strictly necessary (resizing will happen automatically on write, see Javadoc
            // on RandomAccessFile.seek) but seems reasonable since we know the exact size of the resulting file.
            // In the past we filled the file with zeros here, to "overwrite anything that might be in the file already"
            // according to a code comment. However that creates a large burst of disk activity which can run up against
            // IO limits on cloud servers with network storage. Even without initialization, any complete regional analysis
            // would overwrite every byte in the file with a result for some origin point, so the initial values are only
            // important when visualizing or debugging partially completed analysis results.
            this.randomAccessFile = new RandomAccessFile(bufferFile, "rw");
            randomAccessFile.setLength(HEADER_LENGTH_BYTES + (width * height * channels * Integer.BYTES));
            LOG.info(
                    "Created temporary file to accumulate results from workers, size is {}.",
                    human(randomAccessFile.length(), "B")
            );
        } catch (Exception e) {
            throw new RuntimeException("Error initializing regional access grid output file.", e);
        }
    }

    /** Gzip the access grid and upload it to file storage (such as AWS S3). */
    @Override
    public synchronized void finish () throws IOException {
        randomAccessFile.close();
        var gzippedFile = FileUtils.gzipFile(bufferFile);
        fileStorage.moveIntoStorage(new FileStorageKey(FileCategory.RESULTS, gridFileName), gzippedFile);
        bufferFile.delete();
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

    @Override
    public void writeOneWorkResult(RegionalWorkResult workResult) throws Exception {
        // Drop work results for this particular origin into a little-endian output file.
        int[][] percentilesForGrid = workResult.accessibilityValues[destinationIndex];
        int[] cutoffsForPercentile = percentilesForGrid[percentileIndex];
        writeOneOrigin(workResult.taskId, cutoffsForPercentile);
    }

    /**
     * Write all channels at once to the proper subregion of the buffer for this origin. The origins we receive have 2d
     * coordinates. Flatten them to compute file offsets and for the origin checklist.
     */
    private void writeOneOrigin (int taskNumber, int[] values) throws IOException {
        if (values.length != channels) {
            throw new IllegalArgumentException("Number of channels to be written does not match this writer.");
        }
        long offset = HEADER_LENGTH_BYTES + (taskNumber * channels * Integer.BYTES);
        // RandomAccessFile is not threadsafe and multiple threads may call this, so synchronize.
        synchronized (randomAccessFile) {
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
    public synchronized void terminate () throws IOException {
        randomAccessFile.close();
        bufferFile.delete();
    }

}
