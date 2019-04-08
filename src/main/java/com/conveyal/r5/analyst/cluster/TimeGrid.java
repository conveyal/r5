package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.google.common.io.LittleEndianDataOutputStream;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.media.jai.RasterFactory;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Grid for recording travel times, which can be written to flat binary output
 *
 * This is similar to com.conveyal.r5.analyst.Grid, but it uses ints for the pixel values, and allows multiple values
 * per pixel.
 *
 *  * Time grids look like this:
 * Header (ASCII text "TIMEGRID") (note that this header is eight bytes, so the full grid can be mapped into a
 *   Javascript typed array if desired)
 * Version, 4-byte integer
 * (4 byte int) Web mercator zoom level
 * (4 byte int) west (x) edge of the grid, i.e. how many pixels this grid is east of the left edge of the world
 * (4 byte int) north (y) edge of the grid, i.e. how many pixels this grid is south of the top edge of the world
 * (4 byte int) width of the grid in pixels
 * (4 byte int) height of the grid in pixels
 * (4 byte int) number of values per pixel
 * (repeated 4-byte int) values of each pixel in row major order. Values are not delta coded here.
 */
public class TimeGrid {

    public static final Logger LOG = LoggerFactory.getLogger(TimeGrid.class);

    /** 8 bytes long to maintain integer alignment. */
    private static final String gridType = "ACCESSGR";

    /** The offset to get to the data section of the access grid file. The gridType string amounts to 2 ints. */
    public static final int HEADER_SIZE = 7 * Integer.BYTES + gridType.getBytes().length;

    private static final int version = 0;

    // TODO a WEBMERCATOREXTENTS class
    // used to be stored as longs, but can probably still use with impunity without fear of overflow
    private final int zoom, west, north, width, height, nValuesPerPixel;

    // Flattened 1-d array of pixel values
    // FIXME its weird that we're storing this as a flattened array using a completely different order than we're writing out to the file.
    // Should this really be flattenend until it's written out?
    private int[] values;

    public final int nValues;

    /**
     * Create a new in-memory access grid writer for a width x height x nValuesPerPixel 3D array.
     */
    public TimeGrid(int zoom, int west, int north, int width, int height, int nValuesPerPixel) {
        this.zoom = zoom;
        this.west = west;
        this.north = north;
        this.width = width;
        this.height = height;
        this.nValuesPerPixel = nValuesPerPixel;
        this.nValues = width * height * nValuesPerPixel;

        long nBytes = ((long)nValues) * Integer.BYTES + HEADER_SIZE;
        if (nBytes > Integer.MAX_VALUE) {
            throw new RuntimeException("Grid size in bytes exceeds 31-bit addressable space.");
        }

        // Initialization: Fill the values array the default unreachable value.
        // This way the grid is valid even if we don't write anything into it
        // (rather than saying everything is reachable in zero minutes).
        values = new int[nValues];
        for (int i = 0; i < nValues; i ++) {
            values[i] = FastRaptorWorker.UNREACHED;
        }

    }

    // At 2 million destinations and 100 int values per destination (every percentile) we still only are at 800MB.
    // So no real risk of overflowing an int index.
    public void setTarget(int targetIndex, int[] pixelValues) {
        if (pixelValues.length != nValuesPerPixel) {
            throw new IllegalArgumentException("Incorrect number of values per pixel.");
        }
        int index1d = targetIndex * nValuesPerPixel;
        for (int i : pixelValues) {
            values[index1d] = i;
            index1d += 1;
        }
    }

    /**
     * Write the grid out to a persistence buffer, an abstraction that will perform compression and allow us to save
     * it to a local or remote storage location.
     */
    public PersistenceBuffer writeToPersistenceBuffer() {
        PersistenceBuffer persistenceBuffer = new PersistenceBuffer();
        this.writeGridToDataOutput(persistenceBuffer.getDataOutput());
        persistenceBuffer.doneWriting();
        return persistenceBuffer;
    }

    /**
     * Write the grid to an object implementing the DataOutput interface.
     * TODO maybe shrink the dimensions of the resulting timeGrid to contain only the reached cells.
     */
    public void writeGridToDataOutput(DataOutput dataOutput) {
        int sizeInBytes = nValues * Integer.BYTES + HEADER_SIZE;
        LOG.info("Writing travel time surface with uncompressed size {} kiB", sizeInBytes / 1024);
        try {
            // Write header
            dataOutput.write(gridType.getBytes());
            dataOutput.writeInt(version);
            dataOutput.writeInt(zoom);
            dataOutput.writeInt(west);
            dataOutput.writeInt(north);
            dataOutput.writeInt(width);
            dataOutput.writeInt(height);
            dataOutput.writeInt(nValuesPerPixel);
            // Write values, delta coded
            for (int i = 0; i < nValuesPerPixel; i++) {
                int prev = 0; // delta code within each percentile grid
                for (int j = 0; j < width * height; j++) {
                    // FIXME this is doing extra math to rearrange the ordering of the flattened array it's reading.
                    int curr = values[j * nValuesPerPixel + i];
                    // TODO try not delta-coding the "unreachable" value, and retaining the prev value across unreachable areas.
                    int delta = curr - prev;
                    dataOutput.writeInt(delta);
                    prev = curr;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Write this grid out in GeoTIFF format.
     * If an analysis task is supplied, add metadata to the GeoTIFF explaining what scenario it comes from.
     */
    public void writeGeotiff (OutputStream out, AnalysisTask request) {
        LOG.info("Writing GeoTIFF file");
        try {
            // Inspired by org.geotools.coverage.grid.GridCoverageFactory
            final WritableRaster raster =
                    RasterFactory.createBandedRaster(DataBuffer.TYPE_INT, width, height, nValuesPerPixel, null);

            int val;

            for (int y = 0; y < height; y ++) {
                for (int x = 0; x < width; x ++) {
                    for (int n = 0; n < nValuesPerPixel; n ++) {
                        val = values[(y * width + x) * nValuesPerPixel + n];
                        if (val < FastRaptorWorker.UNREACHED) raster.setSample(x, y, n, val);
                    }
                }
            }

            Grid grid = new Grid(zoom, width, height, north, west);
            ReferencedEnvelope env = grid.getMercatorEnvelopeMeters();

            GridCoverageFactory gcf = new GridCoverageFactory();
            GridCoverage2D coverage = gcf.create("TIMEGRID", raster, env);

            GeoTiffWriteParams wp = new GeoTiffWriteParams();
            wp.setCompressionMode(GeoTiffWriteParams.MODE_EXPLICIT);
            wp.setCompressionType("LZW");
            ParameterValueGroup params = new GeoTiffFormat().getWriteParameters();
            params.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString()).setValue(wp);

            GeoTiffWriter writer = new GeoTiffWriter(out);

            // If the request that produced this TimeGrid was supplied, write scenario metadata into the GeoTIFF
            if (request != null) {
                AnalysisTask clonedRequest = request.clone();
                // Save the scenario ID rather than the full scenario, to avoid making metadata too large. We're not
                // losing information here, the scenario id used here is qualified with the CRC and is thus immutable
                // and available from S3.
                if (clonedRequest.scenario != null) {
                    clonedRequest.scenarioId = clonedRequest.scenario.id;
                    clonedRequest.scenario = null;
                }
                // 270: Image Description, 305: Software (https://www.awaresystems.be/imaging/tiff/tifftags/baseline.html)
                writer.setMetadataValue("270", JsonUtilities.objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(clonedRequest));
                writer.setMetadataValue("305", "Conveyal R5");
            }

            writer.write(coverage, params.values().toArray(new GeneralParameterValue[1]));
            writer.dispose();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return true if the search reached any destination cell, false if it did not reach any cells. No cells will be
     * reached when the origin point is outside the transport network. Some cells will still be reached via the street
     * network when we are outside the transit network but within the street network.
     */
    public boolean anyCellReached() {
        return Arrays.stream(values).anyMatch(v -> v != FastRaptorWorker.UNREACHED);
    }

}