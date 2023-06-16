package com.conveyal.r5.analyst;

import com.conveyal.analysis.datasource.DataSourceException;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.util.InputStreamProvider;
import com.conveyal.r5.util.ProgressListener;
import com.conveyal.r5.util.ShapefileReader;
import com.csvreader.CsvReader;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.io.LittleEndianDataOutputStream;
import org.apache.commons.math3.util.FastMath;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.FeatureWriter;
import org.geotools.data.FileDataStore;
import org.geotools.data.FileDataStoreFinder;
import org.geotools.data.Transaction;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedPolygon;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.conveyal.gtfs.util.Util.human;
import static com.conveyal.r5.common.GeometryUtils.checkWgsEnvelopeSize;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Double.parseDouble;
import static org.apache.commons.math3.util.FastMath.atan;
import static org.apache.commons.math3.util.FastMath.cos;
import static org.apache.commons.math3.util.FastMath.log;
import static org.apache.commons.math3.util.FastMath.sinh;
import static org.apache.commons.math3.util.FastMath.tan;

/**
 * Class that represents a grid of opportunity counts in the spherical Mercator "projection" at a given zoom level.
 * This is actually a sub-grid of the full-world web mercator grid, with a specified width and height and offset
 * from the edges of the world. This is quite close to implementing a PointSet interface, but PointSet currently
 * includes linkage cache functionality which Grid does not need.
 *
 * Note that writing a grid out and reading it back in rounds the data values, which start out as fractional doubles.
 * TODO functionality to write and read grids should probably be in a separate class from the main Grid class.
 */
public class Grid extends PointSet {

    public static final Logger LOG = LoggerFactory.getLogger(Grid.class);

    public static final String COUNT_COLUMN_NAME = "[COUNT]";

    public final WebMercatorExtents extents;

    /**
     * The data values for each pixel within this grid. Dimension order is (x, y), with range [0, width) and [0, height).
     */
    public final double[][] grid;

    /** Maximum area allowed for features in a shapefile upload */
    private static final double MAX_FEATURE_AREA_SQ_DEG = 2;

    /** Limit on number of pixels, to prevent OOME when multiple large grids are being created (width * height *
     * number of layers/attributes) */
    private static final int MAX_PIXELS = 10_000 * 10_000 * 10;

    /** Used when reading a saved grid. */
    public Grid (int west, int north, int width, int height, int zoom) {
        this(new WebMercatorExtents(west, north, width, height, zoom));
    }

    /**
     * Other constructors and factory methods all call this one, and Grid has a WebMercatorExtents field, which means
     * Grid construction always follows construction of a WebMercatorExtents, whose constructor always performs a
     * check on the size of the grid.
     */
    public Grid (WebMercatorExtents extents) {
        this.extents = extents;
        this.grid = new double[extents.width][extents.height];
    }

    /**
     * @param zoom Web Mercator zoom level
     * @param wgsEnvelope Envelope of grid, in absolute WGS84 lat/lon coordinates
     */
    public Grid (int zoom, Envelope wgsEnvelope) {
        this(WebMercatorExtents.forWgsEnvelope(wgsEnvelope, zoom));
    }

    public static class PixelWeight {
        public final int x;
        public final int y;
        public final double weight;

        public PixelWeight (int x, int y, double weight){
            this.x = x;
            this.y = y;
            this.weight = weight;
        }
    }

    /**
     * Version of getPixelWeights which returns the weights as relative to the total area of the input geometry (i.e.
     * the weight at a pixel is the proportion of the input geometry that falls within that pixel.
     */
    public List<PixelWeight> getPixelWeights (Geometry geometry) {
        return getPixelWeights(geometry, false);
    }

    /**
     * Get the proportions of an input polygon feature that overlap each grid cell, for use in lists of PixelWeights.
     * These lists can then be fed into the incrementFromPixelWeights function to actually burn a polygon into the
     * grid.
     *
     * If relativeToPixels is true, the weights are the proportion of the pixel that is covered. Otherwise they are the
     * portion of this polygon which is within the given pixel. If using incrementPixelWeights, this should be set to
     * false.
     *
     * This used to return a map from int arrays containing the coordinates to the weight.
     *
     * @param geometry The polygon to intersect with grid cells. Its coordinates must be in WGS84.
     */
    public List<PixelWeight> getPixelWeights (Geometry geometry, boolean relativeToPixels) {
        // No need to convert to a local coordinate system
        // Both the supplied polygon and the web mercator pixel geometries are left in WGS84 geographic coordinates.
        // Both are distorted equally along the X axis at a given latitude so the proportion of the geometry within
        // each pixel is accurate, even though the surface area in WGS84 coordinates is not a usable value.

        List<PixelWeight> weights = new ArrayList<>();

        double area = geometry.getArea();
        if (area < 1e-12) {
            throw new IllegalArgumentException("Feature geometry is too small");
        }

        if (area > MAX_FEATURE_AREA_SQ_DEG) {
            throw new IllegalArgumentException("Feature geometry is too large.");
        }

        // PreparedGeometry is often faster for small numbers of vertices;
        // see https://github.com/chrisbennight/intersection-test
        // We know this is a polygon so don't need flexible PreparedGeometryFactory, which I'd rather not use because
        // its only method seems inherently static but is implemented in a way that requires instantiating the factory.
        PreparedGeometry preparedGeom = new PreparedPolygon((Polygonal) geometry);

        Envelope env = geometry.getEnvelopeInternal();

        for (int worldy = latToPixel(env.getMaxY(), extents.zoom); worldy <= latToPixel(env.getMinY(), extents.zoom); worldy++) {
            // NB web mercator Y is reversed relative to latitude.
            // Iterate over longitude (x) in the inner loop to avoid repeat calculations of pixel areas, which should be
            // equal at a given latitude (y)

            double pixelAreaAtLat = -1; //Set to -1 to recalculate pixelArea at each latitude.

            for (int worldx = lonToPixel(env.getMinX(), extents.zoom); worldx <= lonToPixel(env.getMaxX(), extents.zoom); worldx++) {

                int x = worldx - extents.west;
                int y = worldy - extents.north;

                if (x < 0 || x >= extents.width || y < 0 || y >= extents.height) continue; // off the grid

                Geometry pixel = getPixelGeometry(x , y , extents);
                if (pixelAreaAtLat == -1) pixelAreaAtLat = pixel.getArea(); //Recalculate for a new latitude.

                // Pixel completely within feature:
                // This is an optimization following an example in the online JTS javadoc for containsProperly.
                // The contains (vs. containsProperly) predicate could be used instead, but it is less efficient. In
                // "edge" cases (pun intended) where a polygon contains but does not containProperly the pixel,
                // the correct result will still be calculated below.
                if (preparedGeom.containsProperly(pixel)) {
                    double weight = relativeToPixels ? 1 : pixelAreaAtLat / area;
                    weights.add(new PixelWeight(x, y, weight));
                }
                // Pixel partly within feature:
                else if (preparedGeom.intersects(pixel)){
                    Geometry intersection = pixel.intersection(geometry);
                    double denominator = relativeToPixels ? pixelAreaAtLat : area;
                    double weight = intersection.getArea() / denominator;
                    weights.add(new PixelWeight(x, y, weight));
                }
            }
        }
        return weights;
    }

    /**
     * Do pycnoplactic mapping:
     * the value associated with the supplied polygon will be split out proportionately to
     * all the web Mercator pixels that intersect it.
     *
     * If you are creating multiple grids of the same size for different attributes of the same input features, you should
     * call getPixelWeights(geometry) once for each geometry on any one of the grids, and then pass the returned weights
     * and the attribute value into incrementFromPixelWeights function; this will avoid duplicating expensive geometric
     * math.
     */
    private void rasterize (Geometry geometry, double value) {
        incrementFromPixelWeights(getPixelWeights(geometry), value);
    }

    /** Using a grid of weights produced by getPixelWeights, burn the value of a polygon into the grid. */
    public void incrementFromPixelWeights (List<PixelWeight> weights, double value) {
        for (PixelWeight pix : weights) {
            grid[pix.x][pix.y] += pix.weight * value;
        }
    }

    /**
     * Burn point data into the grid.
     */
    private void incrementPoint (double lat, double lon, double amount) {
        int worldx = lonToPixel(lon, extents.zoom);
        int worldy = latToPixel(lat, extents.zoom);
        int x = worldx - extents.west;
        int y = worldy - extents.north;
        if (x >= 0 && x < extents.width && y >= 0 && y < extents.height) {
            grid[x][y] += amount;
        } else {
            LOG.warn("{} opportunities are outside regional bounds, at {}, {}", amount, lon, lat);
        }
    }

    public void write (String filename) {
        try {
            write(new FileOutputStream(filename));
        } catch (Exception e) {
            throw new RuntimeException("Error writing grid.", e);
        }
    }

    /**
     * Write this opportunity density grid out in R5 binary format.
     * Note that while opportunity densities are internally represented as doubles and don't have to be integers, our
     * file format uses integers so writing a grid rounds off the densities. This can lead to some strange effects
     * described in issue #566. If you rasterize two polygons into the grid, one with 0.49 opportunities in each cell
     * and the other with 0.51, the first polygon will disappear, while the number of opportunities within the second
     * polygon will almost double. If one polygon has 1.2 per cell and the other 0.4 per cell, the first polygon will
     * survive as well as the overlap of the two (which will round to 2) but not the non-overlapping portion of the
     * second polygon.
     * TODO this conversion to integers should happen as separate method, not during writing, and should be better, #566
     * Also note that this is a different format than "access grids" and "time grids". Maybe someday they should all be
     * the same format with a couple of options for compression or number of channels.
     */
    public void write (OutputStream outputStream) throws IOException {
        // Java's DataOutputStream only outputs big-endian format ("network byte order").
        // These grids will be read out of Javascript typed arrays which use the machine's native byte order.
        // On almost all current hardware this is little-endian. Guava saves us again.
        LittleEndianDataOutputStream out = new LittleEndianDataOutputStream(outputStream);
        // A header consisting of six 4-byte integers specifying the zoom level and bounds.
        out.writeInt(extents.zoom);
        out.writeInt(extents.west);
        out.writeInt(extents.north);
        out.writeInt(extents.width);
        out.writeInt(extents.height);
        // The rest of the file is 32-bit integers in row-major order (x changes faster than y), delta-coded.
        // Delta coding and error diffusion are reset on each row to avoid wrapping.
        int prev = 0;
        for (int y = 0; y < extents.height; y++) {
            // Reset error on each row to avoid diffusing to distant locations.
            // An alternative is to use serpentine iteration or iterative diffusion.
            double error = 0;
            for (int x = 0; x < extents.width; x++) {
                double val = grid[x][y];
                checkState(val >= 0, "Opportunity density should never be negative.");
                val += error;
                int rounded = ((int) Math.round(val));
                checkState(rounded >= 0, "Rounded opportunity density should never be negative.");
                error = val - rounded;
                int delta = rounded - prev;
                out.writeInt(delta);
                prev = rounded;
            }
        }
        out.close();
    }

    /** Write this grid out in GeoTIFF format */
    public void writeGeotiff (OutputStream out) {
        try {
            float[][] data = new float[extents.height][extents.width];
            for (int x = 0; x < extents.width; x++) {
                for (int y = 0; y < extents.height; y++) {
                    data[y][x] = (float) grid[x][y];
                }
            }
            ReferencedEnvelope env = this.getWebMercatorExtents().getMercatorEnvelopeMeters();
            GridCoverage2D coverage = new GridCoverageFactory().create("GRID", data, env);
            GeoTiffWriteParams wp = new GeoTiffWriteParams();
            wp.setCompressionMode(GeoTiffWriteParams.MODE_EXPLICIT);
            wp.setCompressionType("LZW");
            ParameterValueGroup params = new GeoTiffFormat().getWriteParameters();
            params.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString()).setValue(wp);
            GeoTiffWriter writer = new GeoTiffWriter(out);
            writer.write(coverage, params.values().toArray(new GeneralParameterValue[1]));
            writer.dispose();
            out.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Note that writing a grid out and reading it back in rounds the data values, which start out as fractional doubles.
     */
    public static Grid read (InputStream inputStream) throws  IOException {
        LittleEndianDataInputStream data = new LittleEndianDataInputStream(inputStream);
        int zoom = data.readInt();
        int west = data.readInt();
        int north = data.readInt();
        int width = data.readInt();
        int height = data.readInt();

        Grid grid = new Grid(west, north, width, height, zoom);

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

        BufferedImage img = new BufferedImage(extents.width, extents.height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] imgPixels = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        int p = 0;
        for (int y = 0; y < extents.height; y++) {
            for (int x = 0; x < extents.width; x++) {
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
            for (int x = 0; x < extents.width; x++) {
                for (int y = 0; y < extents.height; y++) {
                    try {
                        double value = grid[x][y];
                        if (value > 0) {
                            SimpleFeature feature = (SimpleFeature) writer.next();
                            Polygon pixelPolygon = getPixelGeometry(x, y, extents);
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

    public boolean hasEqualExtents(Grid comparisonGrid){
        return this.extents.equals(comparisonGrid.extents);
    }

    /**
     * Return the latitude of the center point of the grid cell for the specified one-dimensional index into this
     * gridded pointset (flattened, with x varying faster than y).
     * TODO WebMercatorGridPointSet should follow a consistent definition (edge or center) but it doesn't!
     *
     * @param i the one-dimensional index into the points composing this pointset
     * @return the WGS84 latitude of the center of the corresponding pixel in this grid, considering its zoom level
     */
    public double getLat(int i) {
        // Integer division of linear index to find vertical integer intra-grid pixel coordinate
        int y = i / extents.width;
        return pixelToCenterLat(extents.north + y, extents.zoom);
    }

    /**
     * Like getLat, but returns the longitude of the center point of the specified cell.
     *
     * @param i the one-dimensional index into the points composing this pointset
     * @return the WGS84 longitude of the center of the corresponding pixel in this grid, considering its zoom level
     */
    public double getLon(int i) {
        // Remainder of division yields horizontal integer intra-grid pixel coordinate
        int x = i % extents.width;
        return pixelToCenterLon(extents.west + x, extents.zoom);
    }

    public int featureCount() {
        return extents.width * extents.height;
    }

    /* functions below from http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Mathematics */

    /**
     * Return the absolute (world) x pixel number of all pixels the given line of longitude falls within at the given
     * zoom level.
     */
    public static int lonToPixel (double lon, int zoom) {
        return (int) ((lon + 180) / 360 * Math.pow(2, zoom) * 256);
    }

    /**
     * Return the longitude of the west edge of any pixel at the given zoom level and x pixel number measured from the
     * west edge of the world (assuming an integer pixel). Noninteger pixels will return locations within that pixel.
     */
    public static double pixelToLon (double xPixel, int zoom) {
        return xPixel / (Math.pow(2, zoom) * 256) * 360 - 180;
    }

    /**
     * Return the longitude of the center of all pixels at the given zoom and x pixel number, measured from the west
     * edge of the world.
     */
    public static double pixelToCenterLon (int xPixel, int zoom) {
        return pixelToLon(xPixel + 0.5, zoom);
    }

    /** Return the absolute (world) y pixel number of all pixels the given line of latitude falls within. */
    public static int latToPixel (double lat, int zoom) {
        double latRad = FastMath.toRadians(lat);
        return (int) ((1 - log(tan(latRad) + 1 / cos(latRad)) / Math.PI) * Math.pow(2, zoom - 1) * 256);
    }

    /**
     * Return the latitude of the center of all pixels at the given zoom level and absolute (world) y pixel number
     * measured southward from the north edge of the world.
     */
    public static double pixelToCenterLat (int yPixel, int zoom) {
        return pixelToLat(yPixel + 0.5, zoom);
    }

    /**
     * Return the latitude of the north edge of any pixel at the given zoom level and y coordinate relative to the top
     * edge of the world (assuming an integer pixel). Noninteger pixels will return locations within the pixel.
     * We're using FastMath here, because the built-in math functions were taking a large amount of time in profiling.
     */
    public static double pixelToLat (double yPixel, int zoom) {
        return FastMath.toDegrees(atan(sinh(Math.PI - (yPixel / 256d) / Math.pow(2, zoom) * 2 * Math.PI)));
    }

    /**
     * Given a pixel's local grid coordinates within the supplied WebMercatorExtents, return a closed
     * polygon of that pixel's outline in WGS84 global geographic coordinates.
     * @param localX x pixel number within the given extents.
     * @param localY y pixel number within the given extents.
     * @return a JTS Polygon in WGS84 coordinates for the given pixel.
     */
    public static Polygon getPixelGeometry (int localX, int localY, WebMercatorExtents extents) {
        int x = localX + extents.west;
        int y = localY + extents.north;
        double minLon = pixelToLon(x, extents.zoom);
        double maxLon = pixelToLon(x + 1, extents.zoom);
        // The y axis increases from north to south in web Mercator.
        double minLat = pixelToLat(y + 1, extents.zoom);
        double maxLat = pixelToLat(y, extents.zoom);
        return GeometryUtils.geometryFactory.createPolygon(new Coordinate[] {
                new Coordinate(minLon, minLat),
                new Coordinate(minLon, maxLat),
                new Coordinate(maxLon, maxLat),
                new Coordinate(maxLon, minLat),
                new Coordinate(minLon, minLat)
        });
    }

    /**
     * @param ignoreFields if this is non-null, the fields with these names will not be considered when looking for
     *                     numeric opportunity count fields. Null strings in the collection are ignored.
     */
    public static List<Grid> fromCsv(InputStreamProvider csvInputStreamProvider,
                                     String latField,
                                     String lonField,
                                     Collection<String> ignoreFields,
                                     int zoom,
                                     ProgressListener progressListener) throws IOException {

        // Read through the CSV file once to establish its structure (which fields are numeric).
        // TODO factor out this logic for all CSV loading, reuse for freeform and grids, set progress properly.
        CsvReader reader = new CsvReader(csvInputStreamProvider.getInputStream(), StandardCharsets.UTF_8);
        reader.readHeaders();
        List<String> headers = Arrays.asList(reader.getHeaders());
        if (!headers.contains(latField)) {
            LOG.info("Lat field not found!");
            throw new IOException("Latitude field not found in CSV.");
        }

        if (!headers.contains(lonField)) {
            LOG.info("Lon field not found!");
            throw new IOException("Longitude field not found in CSV.");
        }

        Envelope envelope = new Envelope();

        // A set to track fields that contain only numeric values, which are candidate opportunity density fields.
        Set<String> numericColumns = new HashSet<>(headers);
        if (numericColumns.size() != headers.size()) {
            throw new IllegalArgumentException("CSV file contains duplicate column headers.");
        }
        if (numericColumns.contains(COUNT_COLUMN_NAME)) {
            throw new IllegalArgumentException("CSV file contains reserved column name: " + COUNT_COLUMN_NAME);
        }
        numericColumns.remove(latField);
        numericColumns.remove(lonField);
        if (ignoreFields != null) {
            for (String fieldName : ignoreFields) {
                if (fieldName != null) {
                    numericColumns.remove(fieldName);
                }
            }
        }

        // Detect which columns are completely numeric by iterating over all the rows and trying to parse the fields.
        int total = 0;
        while (reader.readRecord()) {
            if (++total % 10000 == 0) LOG.info("{} records", human(total));

            envelope.expandToInclude(parseDouble(reader.get(lonField)), parseDouble(reader.get(latField)));

            // Remove columns that cannot be parsed as non-negative finite doubles
            for (Iterator<String> it = numericColumns.iterator(); it.hasNext();) {
                String field = it.next();
                String value = reader.get(field);
                if (value == null || "".equals(value)) continue; // allow missing data TODO add "N/A" etc.?
                try {
                    double dv = parseDouble(value);
                    if (!(Double.isFinite(dv) || dv < 0)) {
                        it.remove(); // TODO track removed columns and report to UI?
                    }
                } catch (NumberFormatException e) {
                    it.remove();
                }
            }
        }

        // This will also close the InputStreams.
        reader.close();

        checkWgsEnvelopeSize(envelope, "CSV points");
        WebMercatorExtents extents = WebMercatorExtents.forWgsEnvelope(envelope, zoom);
        checkPixelCount(extents, numericColumns.size());

        if (progressListener != null) {
            progressListener.setTotalItems(total);
        }

        // We now have an envelope and know which columns are numeric. Make a grid for each numeric column.
        Map<String, Grid> grids = new HashMap<>();
        for (String columnName : numericColumns) {
            Grid grid = new Grid(extents);
            grid.name = columnName;
            grids.put(grid.name, grid);
        }

        // Make one more Grid where every point will have a weight of 1, for counting points rather than opportunities.
        // This assumes there is no column called "[COUNT]" in the source file, which is validated above.
        Grid countGrid = new Grid(extents);
        countGrid.name = COUNT_COLUMN_NAME;
        grids.put(countGrid.name, countGrid);

        // The first read through the CSV just established its structure (e.g. which fields were numeric).
        // Now, re-read the CSV from the beginning to load the values and populate the grids.
        reader = new CsvReader(csvInputStreamProvider.getInputStream(), StandardCharsets.UTF_8);
        reader.readHeaders();

        int i = 0;
        while (reader.readRecord()) {
            if (++i % 1000 == 0) {
                LOG.info("{} records", human(i));
            }

            if (progressListener != null) {
                progressListener.setCompletedItems(i);
            }

            double lat = parseDouble(reader.get(latField));
            double lon = parseDouble(reader.get(lonField));

            // This assumes all columns in the CSV have unique names, which is validated above.
            for (String field : numericColumns) {
                String value = reader.get(field);

                double val;

                if (value == null || "".equals(value)) {
                    val = 0;
                } else {
                    val = parseDouble(value);
                }

                grids.get(field).incrementPoint(lat, lon, val);
            }
            countGrid.incrementPoint(lat, lon, 1);
        }

        // This will also close the InputStreams.
        reader.close();

        return new ArrayList<>(grids.values());
    }

    public static List<Grid> fromShapefile (File shapefile, int zoom) throws IOException, FactoryException, TransformException {
        return fromShapefile(shapefile, zoom, null);
    }

    public static List<Grid> fromShapefile (File shapefile, int zoom, ProgressListener progressListener)
            throws IOException, FactoryException, TransformException {

        ShapefileReader reader = new ShapefileReader(shapefile);
        Envelope envelope = reader.wgs84Bounds();
        checkWgsEnvelopeSize(envelope, "Shapefile");
        WebMercatorExtents extents = WebMercatorExtents.forWgsEnvelope(envelope, zoom);
        List<String> numericAttributes = reader.numericAttributes();
        Set<String> uniqueNumericAttributes = new HashSet<>(numericAttributes);
        if (uniqueNumericAttributes.size() != numericAttributes.size()) {
            throw new IllegalArgumentException("Shapefile has duplicate numeric attributes");
        }
        checkPixelCount(extents, numericAttributes.size());

        int total = reader.featureCount();
        if (progressListener != null) {
            progressListener.setTotalItems(total);
        }

        AtomicInteger count = new AtomicInteger(0);
        Map<String, Grid> grids = new HashMap<>();

        reader.wgs84Stream().forEach(feat -> {
            Geometry geom = (Geometry) feat.getDefaultGeometry();

            for (Property p : feat.getProperties()) {
                Object val = p.getValue();

                if (!(val instanceof Number)) continue;
                double numericVal = ((Number) val).doubleValue();
                if (numericVal == 0) continue;

                String attributeName = p.getName().getLocalPart();
                
                Grid grid = grids.get(attributeName);
                if (grid == null) {
                    grid = new Grid(extents);
                    grid.name = attributeName;
                    grids.put(attributeName, grid);
                }

                if (geom instanceof Point) {
                    Point point = (Point) geom;
                    // already in WGS 84
                    grid.incrementPoint(point.getY(), point.getX(), numericVal);
                } else if (geom instanceof Polygon || geom instanceof MultiPolygon) {
                    grid.rasterize(geom, numericVal);
                } else {
                    throw new IllegalArgumentException("Unsupported geometry type: " + geom);
                }
            }

            int currentCount = count.incrementAndGet();
            if (progressListener != null) {
                progressListener.setCompletedItems(currentCount);
            }
            if (currentCount % 10000 == 0) {
                LOG.info("{} / {} features read", human(currentCount), human(total));
            }
        });
        reader.close();
        return new ArrayList<>(grids.values());
    }

    @Override
    public double sumTotalOpportunities() {
        double totalOpportunities = 0;
        for (double[] values : this.grid) {
            for (double n : values) {
                totalOpportunities += n;
            }
        }
        return totalOpportunities;
    }

    @Override
    public double getOpportunityCount (int i) {
        int x = i % extents.width;
        int y = i / extents.width;
        return grid[x][y];
    }

    /**
     * Rasterize a FreeFormFointSet into a Grid.
     * Currently intended only for UI display of FreeFormPointSets, or possibly for previews of accessibility results
     * during
     */
    public static Grid fromFreeForm (FreeFormPointSet freeForm, int zoom) {
        // TODO make and us a strongly typed WgsEnvelope class here and in various places
        Envelope wgsEnvelope = freeForm.getWgsEnvelope();
        WebMercatorExtents webMercatorExtents = WebMercatorExtents.forWgsEnvelope(wgsEnvelope, zoom);
        Grid grid = new Grid(webMercatorExtents);
        grid.name = freeForm.name;
        for (int f = 0; f < freeForm.featureCount(); f++) {
            grid.incrementPoint(freeForm.getLat(f), freeForm.getLon(f), freeForm.getOpportunityCount(f));
        }
        return grid;
    }

    @Override
    public Envelope getWgsEnvelope () {
        // This should encompass the grid center points but not the grid cells, to fit the method contract.
        throw new UnsupportedOperationException();
    }

    @Override
    public WebMercatorExtents getWebMercatorExtents () {
        return extents;
    }

    public static void checkPixelCount (WebMercatorExtents extents, int layers) {
        int pixels = extents.width * extents.height * layers;
        if (pixels > MAX_PIXELS) {
            throw new DataSourceException("Number of zoom level " + extents.zoom + " pixels (" + pixels + ")"  +
                    "exceeds limit (" + MAX_PIXELS +"). Reduce the zoom level or the file's extents or number of " +
                    "numeric attributes.");
        }
    }



}
