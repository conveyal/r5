package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.FastRaptorWorker;
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
public class TimeGrid extends TravelTimeResult{

    public static final Logger LOG = LoggerFactory.getLogger(TimeGrid.class);

    /** 8 bytes long to maintain integer alignment. */
    private static final String gridType = "ACCESSGR";

    /** The offset to get to the data section of the access grid file. The gridType string amounts to 2 ints. */
    public static final int HEADER_SIZE = 7 * Integer.BYTES + gridType.getBytes().length;

    private static final int version = 0;

    private final WebMercatorExtents extents;

    /**
     * Create a new in-memory access grid writer for a width x height x nValuesPerPixel 3D array.
     */
    public TimeGrid(AnalysisTask task) {
        super(task);
        extents = WebMercatorExtents.forTask(task);

        long nBytes = ((long) nSamplesPerPoint * nPoints) * Integer.BYTES + HEADER_SIZE;
        if (nBytes > Integer.MAX_VALUE) {
            throw new RuntimeException("Grid size in bytes exceeds 31-bit addressable space.");
        }
    }

    /**
     * Write the grid to an object implementing the DataOutput interface.
     * TODO maybe shrink the dimensions of the resulting timeGrid to contain only the reached cells.
     */
    public void writeToDataOutput(DataOutput dataOutput) {
        int sizeInBytes = nSamplesPerPoint * nPoints * Integer.BYTES + HEADER_SIZE;
        LOG.info("Writing travel time surface with uncompressed size {} kiB", sizeInBytes / 1024);
        try {
            // Write header
            dataOutput.write(gridType.getBytes());
            dataOutput.writeInt(version);
            dataOutput.writeInt(extents.zoom);
            dataOutput.writeInt(extents.west);
            dataOutput.writeInt(extents.north);
            dataOutput.writeInt(extents.width);
            dataOutput.writeInt(extents.height);
            dataOutput.writeInt(nSamplesPerPoint);
            // Write values, delta coded
            for (int i = 0; i < nSamplesPerPoint; i++) {
                int prev = 0; // delta code within each percentile grid
                for (int j = 0; j < extents.getArea(); j++) {
                    int curr = values[i][j];
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
     * Write the grid out to a persistence buffer, an abstraction that will perform compression and allow us to save
     * it to a local or remote storage location.
     */
    public PersistenceBuffer writeToPersistenceBuffer() {
        PersistenceBuffer persistenceBuffer = new PersistenceBuffer();
        this.writeToDataOutput(persistenceBuffer.getDataOutput());
        persistenceBuffer.doneWriting();
        return persistenceBuffer;
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
                    RasterFactory.createBandedRaster(DataBuffer.TYPE_INT, extents.width, extents.height,
                            nSamplesPerPoint, null);

            int val;

            for (int y = 0; y < extents.height; y ++) {
                for (int x = 0; x < extents.width; x ++) {
                    for (int n = 0; n < nSamplesPerPoint; n ++) {
                        val = values[n][(y * extents.width + x)];
                        if (val < FastRaptorWorker.UNREACHED) raster.setSample(x, y, n, val);
                    }
                }
            }

            Grid grid = new Grid(extents);
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


}