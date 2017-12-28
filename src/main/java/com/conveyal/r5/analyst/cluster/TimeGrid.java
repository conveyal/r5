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
 * per pixe.
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

    /**
     * The offset to get to the data section of the access grid file
     */
    public static final int HEADER_SIZE = 9 * 4;

    // type of grid
    private String gridType = "ACCESSGR";

    private int version = 0;

    // used to be stored as longs, but can probably still use with impunity without fear of overflow
    private final int zoom, west, north, width, height, nValuesPerPixel;

    // 1-d array of pixel values
    private int[] values;

    public final long nValues;

    /**
     * Create a new in-memory access grid writer for a width x height x nValuesPerPixel array.
     */
    public TimeGrid(int zoom, int west, int north, int width, int height, int nValuesPerPixel) throws IOException {
        this.zoom = zoom;
        this.west = west;
        this.north = north;
        this.width = width;
        this.height = height;
        this.nValuesPerPixel = nValuesPerPixel;
        nValues = width * height * nValuesPerPixel;

        long nBytes = nValues * 4 + HEADER_SIZE;
        if (nBytes > Integer.MAX_VALUE) {
            throw new RuntimeException("Grid size exceeds 31-bit addressable space.");
        }

        values = new int[(int) nValues];

    }

    public void initialize(String gridType, int version){
        if ("ACCESSGR".equals(gridType)){

            this.gridType = gridType;
            this.version = version;

            //Fill the values array the default unreachable value
            for (int i = 0; i < (int) nValues; i ++) {
                values[i] = FastRaptorWorker.UNREACHED;
            }
        } else {
            LOG.warn("Unsupported grid type");
        }
    }

    public void writePixel(int x, int y, int[] pixelValues) throws IOException {
        if (pixelValues.length != nValuesPerPixel)
            throw new IllegalArgumentException("Incorrect number of values per pixel.");

        long index1d = (y * width + x) * nValuesPerPixel;

        if (index1d * 4 + HEADER_SIZE> Integer.MAX_VALUE) {
            throw new RuntimeException("Pixel coordinates are too large for this grid.");
        }

        for (int i : pixelValues) {
            values[(int) index1d] = i;
            index1d ++;
        }
    }

    /** Writes the flat binary format (header, followed by values for each pixel) to an output stream.
     *
     * Uses Java NIO ByteBuffer so ByteOrder.LITTLE_ENDIAN can be set
     * @param out
     * @throws IOException
     */

    public void writeGrid(OutputStream out) throws IOException {

        out.write(writeGrid());
    }

    public byte[] writeGrid() {
        ByteBuffer buffer;

        buffer = ByteBuffer.allocate((int) nValues * 4 + HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Write header
        buffer.put(gridType.getBytes(),0,8);
        buffer.putInt(8, version);
        buffer.putInt(12, zoom);
        buffer.putInt(16, (int) west);
        buffer.putInt(20, (int) north);
        buffer.putInt(24, (int) width);
        buffer.putInt(28, (int) height);
        buffer.putInt(32, (int) nValuesPerPixel);

        //TODO wrap below in gzip output stream, once other changes are made

        // Write values, delta coded
        for (int i = 0; i < nValues; i ++) {
            int prev = 0;
            if (i % nValuesPerPixel == 0) prev = 0;
            int val = values[i] - prev;
            buffer.putInt(HEADER_SIZE + i * 4, val);
            prev = val;
        }

        return buffer.array();
    }


    // TODO refactor to avoid copied/pasted code below from Grid class
    /**
     * How to get the width of the world in meters according to the EPSG CRS spec:
     * $ gdaltransform -s_srs epsg:4326 -t_srs epsg:3857
     * 180, 0
     * 20037508.3427892 -7.08115455161362e-10 0
     * You can't do 180, 90 because this projection is cut off above a certain level to make the world square.
     * You can do the reverse projection to find this latitude:
     * $ gdaltransform -s_srs epsg:3857 -t_srs epsg:4326
     * 20037508.342789, 20037508.342789
     * 179.999999999998 85.0511287798064 0
     */
    public Coordinate mercatorPixelToMeters (double xPixel, double yPixel) {
        double worldWidthPixels = Math.pow(2, zoom) * 256D;
        // Top left is min x and y because y increases toward the south in web Mercator. Bottom right is max x and y.
        // The origin is WGS84 (0,0).
        final double worldWidthMeters = 20037508.342789244 * 2;
        double xMeters = ((xPixel / worldWidthPixels) - 0.5) * worldWidthMeters;
        double yMeters = (0.5 - (yPixel / worldWidthPixels)) * worldWidthMeters; // flip y axis
        return new Coordinate(xMeters, yMeters);
    }

    /**
     * At zoom level zero, our coordinates are pixels in a single planetary tile, with coordinates are in the range
     * [0...256). We want to export with a conventional web Mercator envelope in meters.
     */
    public ReferencedEnvelope getMercatorEnvelopeMeters() {
        Coordinate topLeft = mercatorPixelToMeters(west, north);
        Coordinate bottomRight = mercatorPixelToMeters(west + width, north + height);
        Envelope mercatorEnvelope = new Envelope(topLeft, bottomRight);
        try {
            // Get Spherical Mercator pseudo-projection CRS
            CoordinateReferenceSystem webMercator = CRS.decode("EPSG:3857");
            ReferencedEnvelope env = new ReferencedEnvelope(mercatorEnvelope, webMercator);
            return env;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Write this grid out in GeoTIFF format */
    public void writeGeotiff (OutputStream out) {
        try {
            // Inspired by org.geotools.coverage.grid.GridCoverageFactory
            final WritableRaster raster =
                    RasterFactory.createBandedRaster(DataBuffer.TYPE_INT, width, height, nValuesPerPixel, null);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    for (int n = 0; n < nValuesPerPixel; n ++) {
                        raster.setSample(x, y, 0, values[y * width + x + n]);
                    }
                }
            }

            GridCoverageFactory gcf = new GridCoverageFactory();
            ReferencedEnvelope env = getMercatorEnvelopeMeters();
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