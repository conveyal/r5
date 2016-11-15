package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeometryUtils;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.io.LittleEndianDataOutputStream;
import com.google.common.primitives.Chars;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Polygon;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.data.*;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.coverage.grid.GridCoverage;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Locale;

import static java.lang.Math.atan;
import static java.lang.Math.cos;
import static java.lang.Math.log;
import static java.lang.Math.sinh;
import static java.lang.Math.tan;

/**
 * Class that represents a grid in the spherical Mercator "projection" at a given zoom level.
 * This is actually a sub-grid of the full-world web mercator grid, with a specified width and height and offset from
 * the edges of the world.
 */
public class Grid {

    /** The web mercator zoom level for this grid. */
    public final int zoom;

    /* The following fields establish the position of this sub-grid within the full worldwide web mercator grid. */

    /**
     * The pixel number of the northernmost pixel in this grid (smallest y value in web Mercator,
     * because y increases from north to south in web Mercator).
     */
    public final int north;

    /** The pixel number of the westernmost pixel in this grid (smallest x value). */
    public final int west;

    /** The width of the grid in web Mercator pixels. */
    public final int width;

    /** The height of the grid in web Mercator pixels. */
    public final int height;

    /** The data values for each pixel within this grid. */
    public final double[][] grid;

    /**
     * @param zoom web mercator zoom level for the grid.
     * @param north latitude in decimal degrees of the north edge of this grid.
     * @param east longitude in decimal degrees of the east edge of this grid.
     * @param south latitude in decimal degrees of the south edge of this grid.
     * @param west longitude in decimal degrees of the west edge of this grid.
     */
    public Grid (int zoom, double north, double east, double south, double west) {
        this.zoom = zoom;
        this.north = latToPixel(north, zoom);
        this.height = latToPixel(south, zoom) - this.north;
        this.west = lonToPixel(west, zoom);
        this.width = lonToPixel(east, zoom) - this.west;
        this.grid = new double[width][height];
    }

    /** Used when reading a saved grid. */
    public Grid (int zoom, int width, int height, int north, int west) {
        this.zoom = zoom;
        this.width = width;
        this.height = height;
        this.north = north;
        this.west = west;
        this.grid = new double[width][height];
    }

    /**
     * Do pycnoplactic mapping:
     * the value associated with the supplied polygon a polygon will be split out proportionately to
     * all the web Mercator pixels that intersect it.
     */
    public void rasterize (Geometry geometry, double value) {
        // No need to convert to a local coordinate system
        // Both the supplied polygon and the web mercator pixel geometries are left in WGS84 geographic coordinates.
        // Both are distorted equally along the X axis at a given latitude so the proportion of the geometry within
        // each pixel is accurate, even though the surface area in WGS84 coordinates is not a usable value.

        double area = geometry.getArea();
        if (area < 1e-12) {
            throw new IllegalArgumentException("Geometry is too small");
        }

        Envelope env = geometry.getEnvelopeInternal();
        for (int worldx = lonToPixel(env.getMinX(), zoom); worldx <= lonToPixel(env.getMaxX(), zoom); worldx++) {
            // NB web mercator Y is reversed relative to latitude
            for (int worldy = latToPixel(env.getMaxY(), zoom); worldy <= latToPixel(env.getMinY(), zoom); worldy++) {
                int x = worldx - west;
                int y = worldy - north;

                if (x < 0 || x >= width || y < 0 || y >= height) continue; // off the grid

                Geometry pixel = getPixelGeometry(x + west, y + north, zoom);
                Geometry intersection = pixel.intersection(geometry);
                double weight = intersection.getArea() / area;
                grid[x][y] += weight * value;
            }
        }
    }

    /**
     * Burn point data into the grid.
     */
    public void incrementPoint (double lat, double lon, double amount) {
        int worldx = lonToPixel(lon, zoom);
        int worldy = latToPixel(lat, zoom);
        int x = worldx - west;
        int y = worldy - north;
        if (x >= 0 && x < width && y >= 0 && y < height) {
            grid[x][y] += amount;
        } else {
            // Warn that an attempt was made to increment outside the grid
        }
    }

    /** Write this grid out in R5 binary grid format. */
    public void write (OutputStream outputStream) throws IOException {
        // Java's DataOutputStream only outputs big-endian format ("network byte order").
        // These grids will be read out of Javascript typed arrays which use the machine's native byte order.
        // On almost all current hardware this is little-endian. Guava saves us again.
        LittleEndianDataOutputStream out = new LittleEndianDataOutputStream(outputStream);
        // A header consisting of six 4-byte integers specifying the zoom level and bounds.
        out.writeInt(zoom);
        out.writeInt(west);
        out.writeInt(north);
        out.writeInt(width);
        out.writeInt(height);
        // The rest of the file is 32-bit integers in row-major order (x changes faster than y), delta-coded.
        for (int y = 0, prev = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = (int) Math.round(grid[x][y]);
                out.writeInt(val - prev);
                prev = val;
            }
        }
        out.close();
    }

    /** Write this grid out in GeoTIFF format */
    public void writeGeotiff (OutputStream out) {
        try {
            Coordinate topLeft = new Coordinate(pixelToLon(west, zoom), pixelToLat(north, zoom));
            Coordinate bottomRight = new Coordinate(pixelToLon(west + width, zoom), pixelToLat(north + height, zoom));

            Envelope envelopeWgs = new Envelope(topLeft, bottomRight);

            // TODO fix projection
            // This is not strictly correct, the data are not WGS 84. However, we're saying what the bounds are so
            // the data will be stretched to fit; the only issue is the variation in scale over the map, which is
            // small in the small areas we're working with. After two hours of trying to find/make an appropriate CRS
            // for what we're doing, I resorted to this.
            ReferencedEnvelope env = new ReferencedEnvelope(envelopeWgs, DefaultGeographicCRS.WGS84);

            float[][] data = new float[height][width];

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    data[y][x] = (float) grid[x][y];
                }
            }

            GridCoverage2D coverage = new GridCoverageFactory().create("GRID", data, env);

            GeoTiffWriteParams wp = new GeoTiffWriteParams();
            wp.setCompressionMode(GeoTiffWriteParams.MODE_EXPLICIT);
            wp.setCompressionType("LZW");
            ParameterValueGroup params = new GeoTiffFormat().getWriteParameters();
            params.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString()).setValue(wp);
            GeoTiffWriter writer = new GeoTiffWriter(out);
            writer.write(coverage, (GeneralParameterValue[]) params.values().toArray(new GeneralParameterValue[1]));
            writer.dispose();
            out.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Grid read (InputStream inputStream) throws  IOException {
        LittleEndianDataInputStream data = new LittleEndianDataInputStream(inputStream);
        int zoom = data.readInt();
        int west = data.readInt();
        int north = data.readInt();
        int width = data.readInt();
        int height = data.readInt();

        Grid grid = new Grid(zoom, width, height, north, west);

        // loop in row-major order
        for (int y = 0, value = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                grid.grid[x][y] = (value += data.readInt());
            }
        }

        data.close();

        return grid;
    }

    /** Write this grid out to a normalized grayscale image in PNG format. */
    public void writePng(OutputStream outputStream) throws IOException {
        // Find maximum pixel value to normalize brightness
        double maxPixel = 0;
        for (double[] row : grid) {
            for (double value : row) {
                if (value > maxPixel) {
                    maxPixel = value;
                }
            }
        }

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] imgPixels = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        int p = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double density = grid[x][y];
                imgPixels[p++] = (byte)(density * 255 / maxPixel);
            }
        }

        ImageIO.write(img, "png", outputStream);
        outputStream.close();
    }

    /** Write this grid out as an ESRI Shapefile. */
    public void writeShapefile (String fileName, String fieldName) {
        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName("Mercator Grid");
        builder.setCRS(DefaultGeographicCRS.WGS84);
        builder.add("the_geom", Polygon.class);
        builder.add(fieldName, Double.class);
        final SimpleFeatureType gridCell = builder.buildFeatureType();
        try {
            FileDataStore store = FileDataStoreFinder.getDataStore(new File(fileName));
            store.createSchema(gridCell);
            Transaction transaction = new DefaultTransaction("Save Grid");
            FeatureWriter writer = store.getFeatureWriterAppend(transaction);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    try {
                        double value = grid[x][y];
                        if (value > 0) {
                            SimpleFeature feature = (SimpleFeature) writer.next();
                            Polygon pixelPolygon = getPixelGeometry(x + west, y + north, zoom);
                            feature.setDefaultGeometry(pixelPolygon);
                            feature.setAttribute(fieldName, value);
                            writer.write();
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            transaction.commit();
            writer.close();
            store.dispose();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /* functions below from http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Mathematics */

    public static int lonToPixel (double lon, int zoom) {
        return (int) ((lon + 180) / 360 * Math.pow(2, zoom) * 256);
    }

    public static double pixelToLon (double pixel, int zoom) {
        return pixel / (Math.pow(2, zoom) * 256) * 360 - 180;
    }

    public static int latToPixel (double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        return (int) ((1 - log(tan(latRad) + 1 / cos(latRad)) / Math.PI) * Math.pow(2, zoom - 1) * 256);
    }

    public static double pixelToLat (double pixel, int zoom) {
        return Math.toDegrees(atan(sinh(Math.PI - (pixel / 256d) / Math.pow(2, zoom) * 2 * Math.PI)));
    }

    /**
     * @param x absolute (world) x pixel number at the given zoom level.
     * @param y absolute (world) y pixel number at the given zoom level.
     * @return a JTS Polygon in WGS84 coordinates for the given absolute (world) pixel.
     */
    public static Polygon getPixelGeometry (int x, int y, int zoom) {
        double minLon = pixelToLon(x, zoom);
        double maxLon = pixelToLon(x + 1, zoom);
        // The y axis increases from north to south in web Mercator.
        double minLat = pixelToLat(y + 1, zoom);
        double maxLat = pixelToLat(y, zoom);
        return GeometryUtils.geometryFactory.createPolygon(new Coordinate[] {
                new Coordinate(minLon, minLat),
                new Coordinate(minLon, maxLat),
                new Coordinate(maxLon, maxLat),
                new Coordinate(maxLon, minLat),
                new Coordinate(minLon, minLat)
        });
    }
}
