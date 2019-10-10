package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.util.ShapefileReader;
import com.csvreader.CsvReader;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.io.LittleEndianDataOutputStream;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.prep.PreparedGeometry;
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory;
import org.apache.commons.io.input.BOMInputStream;
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
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.conveyal.gtfs.util.Util.human;
import static java.lang.Double.parseDouble;
import static org.apache.commons.math3.util.FastMath.atan;
import static org.apache.commons.math3.util.FastMath.cos;
import static org.apache.commons.math3.util.FastMath.log;
import static org.apache.commons.math3.util.FastMath.sinh;
import static org.apache.commons.math3.util.FastMath.tan;

/**
 * Class that represents a grid in the spherical Mercator "projection" at a given zoom level.
 * This is actually a sub-grid of the full-world web mercator grid, with a specified width and height and offset from
 * the edges of the world.
 */
public class Grid {

    public static final Logger LOG = LoggerFactory.getLogger(Grid.class);

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

    /** Maximum area allowed for the bounding box of an uploaded shapefile -- large enough for New York State.  */
    private static final double MAX_BOUNDING_BOX_AREA_SQ_KM = 250_000;

    /** Maximum area allowed for features in a shapefile upload */
    double MAX_FEATURE_AREA_SQ_DEG = 2;

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
        /**
         * The grid extent is computed from the points. If the cell number for the right edge of the grid is rounded
         * down, some points could fall outside the grid. `latToPixel` and `lonToPixel` naturally round down - which is
         * the correct behavior for binning points into cells but means the grid is always 1 row too narrow/short.
         *
         * So we add 1 to the height and width when a grid is created in this manner.
         */
        this.height = (latToPixel(south, zoom) - this.north) + 1; // minimum height is 1
        this.west = lonToPixel(west, zoom);
        this.width = (lonToPixel(east, zoom) - this.west) + 1; // minimum width is 1
        this.grid = new double[width][height];
    }

    /**
     * Used when reading a saved grid.
     * FIXME we have two constructors with five numeric parameters, differentiated only by int/double type.
     */
    public Grid (int zoom, int width, int height, int north, int west) {
        this.zoom = zoom;
        this.width = width;
        this.height = height;
        this.north = north;
        this.west = west;
        this.grid = new double[width][height];
    }

    public static class PixelWeight {
        public final int x;
        public final int y;
        public final double weight;

        private PixelWeight (int x, int y, double weight){
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

    // PreparedGeometry is often faster for small numbers of vertices;
    // see https://github.com/chrisbennight/intersection-test
    private PreparedGeometryFactory pgFact = new PreparedGeometryFactory();

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

        PreparedGeometry preparedGeom = pgFact.create(geometry);

        Envelope env = geometry.getEnvelopeInternal();

        for (int worldy = latToPixel(env.getMaxY(), zoom); worldy <= latToPixel(env.getMinY(), zoom); worldy++) {
            // NB web mercator Y is reversed relative to latitude.
            // Iterate over longitude (x) in the inner loop to avoid repeat calculations of pixel areas, which should be
            // equal at a given latitude (y)

            double pixelAreaAtLat = -1; //Set to -1 to recalculate pixelArea at each latitude.

            for (int worldx = lonToPixel(env.getMinX(), zoom); worldx <= lonToPixel(env.getMaxX(), zoom); worldx++) {

                int x = worldx - west;
                int y = worldy - north;

                if (x < 0 || x >= width || y < 0 || y >= height) continue; // off the grid

                Geometry pixel = getPixelGeometry(x + west, y + north, zoom);

                if (pixelAreaAtLat == -1) pixelAreaAtLat = pixel.getArea(); //Recalculate for a new latitude.

                if (preparedGeom.intersects(pixel)){ // pixel is at least partly inside the feature
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

    /** Using a grid of weights produced by getPixelWeights, burn the value of a polygon into the grid */
    public void incrementFromPixelWeights (List weights, double value) {
        Iterator<PixelWeight> pixelWeightIterator = weights.iterator();
        while(pixelWeightIterator.hasNext()) {
            PixelWeight pix = pixelWeightIterator.next();
            grid[pix.x][pix.y] += pix.weight * value;
        }
    }

    /**
     * Burn point data into the grid.
     */
    private void incrementPoint (double lat, double lon, double amount) {
        int worldx = lonToPixel(lon, zoom);
        int worldy = latToPixel(lat, zoom);
        int x = worldx - west;
        int y = worldy - north;
        if (x >= 0 && x < width && y >= 0 && y < height) {
            grid[x][y] += amount;
        } else {
            LOG.warn("{} opportunities are outside regional bounds, at {}, {}", amount, lon, lat);
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
            float[][] data = new float[height][width];
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    data[y][x] = (float) grid[x][y];
                }
            }
            ReferencedEnvelope env = getMercatorEnvelopeMeters();
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

    public boolean hasEqualExtents(Grid comparisonGrid){
        return this.zoom == comparisonGrid.zoom && this.west == comparisonGrid.west && this.north == comparisonGrid.north && this.width == comparisonGrid.width && this.height == comparisonGrid.height;
    }

    /* functions below from http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Mathematics */

    /** Return the pixel the given longitude falls within */
    public static int lonToPixel (double lon, int zoom) {
        return (int) ((lon + 180) / 360 * Math.pow(2, zoom) * 256);
    }

    /** return the west side of the given pixel (assuming an integer pixel; noninteger pixels will return the appropriate location within the pixel) */
    public static double pixelToLon (double pixel, int zoom) {
        return pixel / (Math.pow(2, zoom) * 256) * 360 - 180;
    }

    /** Return the longitude of the center of the given pixel */
    public static double pixelToCenterLon (int pixel, int zoom) {
        return pixelToLon(pixel + 0.5, zoom);
    }

    /** Return the pixel the given latitude falls within */
    public static int latToPixel (double lat, int zoom) {
        double latRad = FastMath.toRadians(lat);
        return (int) ((1 - log(tan(latRad) + 1 / cos(latRad)) / Math.PI) * Math.pow(2, zoom - 1) * 256);
    }

    /** Return the latitude of the center of the given pixel */
    public static double pixelToCenterLat (int pixel, int zoom) {
        return pixelToLat(pixel + 0.5, zoom);
    }

    // We're using FastMath here, because the built-in math functions were taking a large amount of time in profiling.
    /** return the north side of the given pixel (assuming an integer pixel; noninteger pixels will return the appropriate location within the pixel) */
    public static double pixelToLat (double pixel, int zoom) {
        return FastMath.toDegrees(atan(sinh(Math.PI - (pixel / 256d) / Math.pow(2, zoom) * 2 * Math.PI)));
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

    /** Create grids from a CSV file */
    public static Map<String,Grid> fromCsv(File csvFile, String latField, String lonField, int zoom) throws IOException {
        return fromCsv(csvFile, latField, lonField, zoom, null);
    }

    public static Map<String,Grid> fromCsv(File csvFile, String latField, String lonField, int zoom, BiConsumer<Integer, Integer> statusListener) throws IOException {

        // Read through the CSV file once to establish its structure (which fields are numeric).
        // Although UTF-8 encoded files do not need a byte order mark and it is not recommended, Windows text editors
        // often add one anyway.
        InputStream csvInputStream = new BOMInputStream(new BufferedInputStream(new FileInputStream(csvFile)));
        CsvReader reader = new CsvReader(csvInputStream, Charset.forName("UTF-8"));
        reader.readHeaders();
        String[] headers = reader.getHeaders();
        if (!Stream.of(headers).anyMatch(h -> h.equals(latField))) {
            LOG.info("Lat field not found!");
            throw new IOException("Latitude field not found in CSV.");
        }

        if (!Stream.of(headers).anyMatch(h -> h.equals(lonField))) {
            LOG.info("Lon field not found!");
            throw new IOException("Longitude field not found in CSV.");
        }

        Envelope envelope = new Envelope();

        // Keep track of which fields contain numeric values
        Set<String> numericColumns = Stream.of(headers).collect(Collectors.toCollection(HashSet::new));
        numericColumns.remove(latField);
        numericColumns.remove(lonField);

        // Detect which columns are completely numeric by iterating over all the rows and trying to parse the fields
        int total = 0;
        while (reader.readRecord()) {
            if (++total % 10000 == 0) LOG.info("{} records", human(total));

            envelope.expandToInclude(parseDouble(reader.get(lonField)), parseDouble(reader.get(latField)));

            // Remove columns that cannot be parsed as doubles
            for (Iterator<String> it = numericColumns.iterator(); it.hasNext();) {
                String field = it.next();
                String value = reader.get(field);
                if (value == null || "".equals(value)) continue; // allow missing data
                try {
                    // TODO also exclude columns containing negatives?
                    parseDouble(value);
                } catch (NumberFormatException e) {
                    it.remove();
                }
            }
        }

        reader.close();

        if (statusListener != null) statusListener.accept(0, total);

        // We now have an envelope and know which columns are numeric
        // Make a grid for each numeric column
        Map<String, Grid> grids = numericColumns.stream()
                .collect(
                        Collectors.toMap(
                                c -> c,
                                c -> new Grid(
                                        zoom,
                                        envelope.getMaxY(),
                                        envelope.getMaxX(),
                                        envelope.getMinY(),
                                        envelope.getMinX()
                                )));

        // The first read through the CSV just established its structure (e.g. which fields were numeric).
        // Now, re-read the CSV from the beginning to load the values and populate the grids.
        csvInputStream = new BOMInputStream(new BufferedInputStream(new FileInputStream(csvFile)));
        reader = new CsvReader(csvInputStream, Charset.forName("UTF-8"));
        reader.readHeaders();

        int i = 0;
        while (reader.readRecord()) {
            if (++i % 1000 == 0) {
                LOG.info("{} records", human(i));
            }

            if (statusListener != null) statusListener.accept(i, total);

            double lat = parseDouble(reader.get(latField));
            double lon = parseDouble(reader.get(lonField));

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
        }

        reader.close();

        return grids;
    }

    public static Map<String, Grid> fromShapefile (File shapefile, int zoom) throws IOException, FactoryException, TransformException {
        return fromShapefile(shapefile, zoom, null);
    }

    public static Map<String, Grid> fromShapefile (File shapefile, int zoom, BiConsumer<Integer, Integer> statusListener) throws IOException, FactoryException, TransformException {
        Map<String, Grid> grids = new HashMap<>();
        ShapefileReader reader = new ShapefileReader(shapefile);

        // TODO looks like this calculates square km in web mercator, which is heavily distorted away from the equator.
        double boundingBoxAreaSqKm = reader.getAreaSqKm();

        if (boundingBoxAreaSqKm > MAX_BOUNDING_BOX_AREA_SQ_KM){
            throw new IllegalArgumentException("Shapefile extent (" + boundingBoxAreaSqKm + " sq. km.) exceeds limit (" +
                    MAX_BOUNDING_BOX_AREA_SQ_KM + "sq. km.).");
        }

        Envelope envelope = reader.wgs84Bounds();
        int total = reader.getFeatureCount();

        if (statusListener != null) statusListener.accept(0, total);

        AtomicInteger count = new AtomicInteger(0);

        reader.wgs84Stream().forEach(feat -> {
            Geometry geom = (Geometry) feat.getDefaultGeometry();

            for (Property p : feat.getProperties()) {
                Object val = p.getValue();

                if (val == null || !Number.class.isInstance(val)) continue;
                double numericVal = ((Number) val).doubleValue();
                if (numericVal == 0) continue;

                String attributeName = p.getName().getLocalPart();

                if (!grids.containsKey(attributeName)) {
                    grids.put(attributeName, new Grid(
                            zoom,
                            envelope.getMaxY(),
                            envelope.getMaxX(),
                            envelope.getMinY(),
                            envelope.getMinX()
                    ));
                }

                Grid grid = grids.get(attributeName);

                if (geom instanceof Point) {
                    Point point = (Point) geom;
                    // already in WGS 84
                    grid.incrementPoint(point.getY(), point.getX(), numericVal);
                } else if (geom instanceof Polygon || geom instanceof MultiPolygon) {
                    grid.rasterize(geom, numericVal);
                } else {
                    throw new IllegalArgumentException("Unsupported geometry type");
                }
            }

            int currentCount = count.incrementAndGet();

            if (statusListener != null) statusListener.accept(currentCount, total);

            if (currentCount % 10000 == 0) {
                LOG.info("{} / {} features read", human(currentCount), human(total));
            }
        });

        reader.close();
        return grids;
    }
}
