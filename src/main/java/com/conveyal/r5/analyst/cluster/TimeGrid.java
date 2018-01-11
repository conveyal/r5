package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.media.jai.RasterFactory;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
    public static final Logger LOG = LoggerFactory.getLogger(GridResultAssembler.class);

    /** 8 bytes long to maintain integer alignment. */
    private static final String gridType = "ACCESSGR";

    /** The offset to get to the data section of the access grid file. The gridType string amounts to 2 ints. */
    public static final int HEADER_SIZE = 7 * Integer.BYTES + gridType.getBytes().length;

    private static final int version = 0;

    // TODO a WEBMERCATOREXTENTS class
    // used to be stored as longs, but can probably still use with impunity without fear of overflow
    private final int zoom, west, north, width, height, nValuesPerPixel;

    // Flattened 1-d array of pixel values
    private int[] values;

    public final int nValues;

    /**
     * Create a new in-memory access grid writer for a width x height x nValuesPerPixel array.
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
     * Writes the flat binary format (header, followed by values for each pixel) to an output stream.
     *
     * Uses Java NIO ByteBuffer so ByteOrder.LITTLE_ENDIAN can be set
     * @param out
     * @throws IOException
     */
    public void writeGrid(OutputStream out) throws IOException {

        out.write(writeGrid());
    }

    // FIXME why don't we write this straight to a stream instead of buffering and doing address math?
    // Do we really want to buffer this in memory?
    // It seems like the main reason is just getting little-endian byte order for JavaScript typed arrays.
    // Could we use a Guava little-endian output stream?
    public byte[] writeGrid() {
        ByteBuffer buffer = ByteBuffer.allocate(nValues * Integer.BYTES + HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        // Write header
        buffer.put(gridType.getBytes());
        buffer.putInt(version);
        buffer.putInt(zoom);
        buffer.putInt(west);
        buffer.putInt(north);
        buffer.putInt(width);
        buffer.putInt(height);
        buffer.putInt(nValuesPerPixel);

        //TODO wrap below in gzip output stream, once other changes are made

        // Write values, delta coded TODO why are we delta-coding within pixels?
        int i = 0;
        while (i < nValues) {
            int prev = 0;
            for (int v = 0; v < nValuesPerPixel; v++, i++) {
                int curr = values[i];
                int delta = curr - prev;
                buffer.putInt(delta);
                prev = curr;
            }
        }

        return buffer.array();
    }

    /** Write this grid out in GeoTIFF format */
    public void writeGeotiff (OutputStream out) {
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
            writer.write(coverage, params.values().toArray(new GeneralParameterValue[1]));
            writer.dispose();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}