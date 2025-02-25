package com.conveyal.analysis.results;

import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.LittleEndianIntOutputStream;
import com.conveyal.r5.analyst.WebMercatorExtents;
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
import java.util.HashMap;
import java.util.Map;

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
public class GridResultWriter extends BaseResultWriter {

    private static final Logger LOG = LoggerFactory.getLogger(GridResultWriter.class);

    private final RandomAccessFile randomAccessFile;

    /** The version of the access grids we produce */
    private static final int ACCESS_GRID_VERSION = 0;

    /** The offset to get to the data section of the access grid file. */
    private static final long HEADER_LENGTH_BYTES = 9 * Integer.BYTES;

    private final GridResultType gridResultType;

    /**
     * The number of thresholds (time or cumulative access) stored in the grid and used for computing values per origin.
     *
     * Note: Only the number of thresholds is stored, not their values. Proper interpretation requires threshold values
     * from the regional analysis. Storing values would require a file format change.
     */
    protected final int nThresholds;

    protected final int destinationIndex;

    protected final int percentileIndex;

    static Map<FileStorageKey, GridResultWriter> createGridResultWritersForTask(RegionalTask task, RegionalAnalysis regionalAnalysis) {
        WebMercatorExtents extents = task.getWebMercatorExtents();
        Map<FileStorageKey, GridResultWriter> writers = new HashMap<>();
        for (int destinationsIndex = 0; destinationsIndex < task.destinationPointSetKeys.length; destinationsIndex++) {
            for (int percentilesIndex = 0; percentilesIndex < task.percentiles.length; percentilesIndex++) {
                if (task.recordAccessibility) {
                    FileStorageKey fileKey = RegionalAnalysis.getMultiOriginAccessFileKey(
                        task.jobId,
                        regionalAnalysis.destinationPointSetIds[destinationsIndex],
                        task.percentiles[percentilesIndex]
                    );
                    writers.put(fileKey, new GridResultWriter(
                            GridResultType.ACCESS,
                            extents,
                            destinationsIndex,
                            percentilesIndex,
                            task.cutoffsMinutes.length
                    ));
                } 

                if (task.includeTemporalDensity) {
                    FileStorageKey fileKey = RegionalAnalysis.getMultiOriginDualAccessFileKey(
                        task.jobId,
                        regionalAnalysis.destinationPointSetIds[destinationsIndex],
                        task.percentiles[percentilesIndex]
                    );
                    writers.put(fileKey, new GridResultWriter(
                            GridResultType.DUAL,
                            extents,
                            destinationsIndex,
                            percentilesIndex,
                            task.dualAccessThresholds.length
                    ));
                }
            }
        }
        return writers;
    }

    /**
     * Construct a writer for a single regional analysis result grid, using the proprietary
     * Conveyal grid format. This also creates the on-disk scratch buffer into which the results
     * from the workers will be accumulated.
     */
    GridResultWriter(GridResultType gridResultType, WebMercatorExtents ext, int destinationIndex, int percentileIndex, int nThresholds) {
        long bodyBytes = (long) ext.width * ext.height * nThresholds * Integer.BYTES;
        this.gridResultType = gridResultType;
        this.nThresholds = nThresholds;
        this.destinationIndex = destinationIndex;
        this.percentileIndex = percentileIndex;
        LOG.info(
            "Expecting multi-origin results for grid with width {}, height {}, {} values per origin.",
                ext.width,
                ext.height,
                nThresholds
        );

        try {
            // Write the access grid file header to the temporary file.
            FileOutputStream fos = new FileOutputStream(bufferFile);
            LittleEndianIntOutputStream data = new LittleEndianIntOutputStream(fos);
            data.writeAscii("ACCESSGR");
            data.writeInt(ACCESS_GRID_VERSION);
            data.writeInt(ext.zoom);
            data.writeInt(ext.west);
            data.writeInt(ext.north);
            data.writeInt(ext.width);
            data.writeInt(ext.height);
            data.writeInt(nThresholds);
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
            randomAccessFile = new RandomAccessFile(bufferFile, "rw");
            randomAccessFile.setLength(HEADER_LENGTH_BYTES + bodyBytes);
            LOG.info(
                    "Created temporary file to accumulate results from workers, size is {}.",
                    human(randomAccessFile.length(), "B")
            );
        } catch (Exception e) {
            throw new RuntimeException("Error initializing regional access grid output file.", e);
        }
    }

    @Override
    public void writeOneWorkResult (RegionalWorkResult workResult) throws IOException {
        if (gridResultType == GridResultType.ACCESS) {
            writeOneOrigin(workResult.taskId, workResult.accessibilityValues[destinationIndex][percentileIndex]);
        } else {
            writeOneOrigin(workResult.taskId, workResult.dualAccessValues[destinationIndex][percentileIndex]);
        }
    }

    /** Clean up the random access file and return the buffer file. */
    public synchronized File finish() throws IOException {
        randomAccessFile.close();
        return bufferFile;
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
     * Write all values at once to the proper subregion of the buffer for this origin. The origins we receive have 2d
     * coordinates. Flatten them to compute file offsets and for the origin checklist.
     */
    protected synchronized void writeOneOrigin (int taskNumber, int[] values) throws IOException {
        if (values.length != nThresholds) {
            throw new IllegalArgumentException("Number of thresholds to be written does not match this writer.");
        }
        long offset = HEADER_LENGTH_BYTES + ((long) taskNumber * nThresholds * Integer.BYTES);
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
    public synchronized void terminate () throws IOException {
        randomAccessFile.close();
        bufferFile.delete();
    }
}
