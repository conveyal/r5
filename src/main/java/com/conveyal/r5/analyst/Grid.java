package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeoJsonFeature;
import com.conveyal.r5.common.GeoJsonFeatureCollection;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.common.JsonUtilities;
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
import com.vividsolutions.jts.geom.Polygonal;
import gnu.trove.iterator.TObjectDoubleIterator;
import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.map.hash.TObjectDoubleHashMap;
import org.apache.commons.math3.util.FastMath;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.data.*;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.crs.DefaultGeographicCRS;
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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.conveyal.gtfs.util.Util.human;
import static java.lang.Double.parseDouble;
import static org.apache.commons.math3.util.FastMath.atan;
import static org.apache.commons.math3.util.FastMath.cos;
import static org.apache.commons.math3.util.FastMath.log;
import static org.apache.commons.math3.util.FastMath.sinh;
import static org.apache.commons.math3.util.FastMath.tan;
import static spark.Spark.halt;

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
     * Get the proportions of an input polygon feature that overlap each grid cell, in the format [x, y] => weight.
     * This weight object can then be fed into the incrementFromPixelWeights function to actually burn a polygon into the
     * grid.
     */
    public TObjectDoubleMap<int[]> getPixelWeights (Geometry geometry) {
        // No need to convert to a local coordinate system
        // Both the supplied polygon and the web mercator pixel geometries are left in WGS84 geographic coordinates.
        // Both are distorted equally along the X axis at a given latitude so the proportion of the geometry within
        // each pixel is accurate, even though the surface area in WGS84 coordinates is not a usable value.

        TObjectDoubleMap<int[]> weights = new TObjectDoubleHashMap<>();

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
                weights.put(new int[] { x, y }, weight);
            }
        }

        return weights;
    }

    /**
     * Do pycnoplactic mapping:
     * the value associated with the supplied polygon a polygon will be split out proportionately to
     * all the web Mercator pixels that intersect it.
     *
     * If you are creating multiple grids of the same size for different attributes of the same input features, you should
     * call getPixelWeights(geometry) once for each geometry on any one of the grids, and then pass the returned weights
     * and the attribute value into incrementFromPixelWeights function; this will avoid duplicating expensive geometric
     * math.
     */
    public void rasterize (Geometry geometry, double value) {
        incrementFromPixelWeights(getPixelWeights(geometry), value);
    }

    /** Using a grid of weights produced by getPixelWeights, burn the value of a polygon into the grid */
    public void incrementFromPixelWeights (TObjectDoubleMap<int[]> weights, double value) {
        for (TObjectDoubleIterator<int[]> it = weights.iterator(); it.hasNext();) {
            it.advance();
            grid[it.key()[0]][it.key()[1]] += it.value() * value;
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
        double latRad = FastMath.toRadians(lat);
        return (int) ((1 - log(tan(latRad) + 1 / cos(latRad)) / Math.PI) * Math.pow(2, zoom - 1) * 256);
    }

    // We're using FastMath here, because the built-in math functions were taking a laarge amount of time in profiling.
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
        CsvReader reader = new CsvReader(new BufferedInputStream(new FileInputStream(csvFile)), Charset.forName("UTF-8"));
        reader.readHeaders();

        String[] headers = reader.getHeaders();
        if (!Stream.of(headers).filter(h -> h.equals(latField)).findAny().isPresent()) {
            LOG.info("Lat field not found!");
            halt(400, "Lat field not found");
        }

        if (!Stream.of(headers).filter(h -> h.equals(lonField)).findAny().isPresent()) {
            LOG.info("Lon field not found!");
            halt(400, "Lon field not found");
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

        // read it again, Sam - reread the CSV to get the actual values and populate the grids
        reader = new CsvReader(new BufferedInputStream(new FileInputStream(csvFile)), Charset.forName("UTF-8"));
        reader.readHeaders();

        int i = 0;
        while (reader.readRecord()) {
            if (++i % 10000 == 0) {
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

    /** Perform various operations on grids
     *  e.g. Grid sum grid1 grid2 ... output.grid
     */
    public static void main (String... args) throws IOException {
        if ("sum".equals(args[0])) {
            Grid[] inGrids = new Grid[args.length - 2];
            for (int i = 1; i < args.length - 1; i++) {
                InputStream is = new BufferedInputStream(new FileInputStream(args[i]));
                Grid g = Grid.read(is);
                is.close();

                if (i > 1) {
                    if (!g.hasSameZoomAndBoundsAs(inGrids[0])) {
                        LOG.error("Grid {} does not have same bounds and zoom as grid {}", args[i], args[1]);
                        System.exit(1);
                    }
                }

                inGrids[i - 1] = g;
            }

            // sum in place
            Grid result = inGrids[0];
            for (int i = 1; i < inGrids.length; i++) {
                Grid current = inGrids[i];
                for (int x = 0; x < result.width; x++) {
                    for (int y = 0; y < result.height; y++) {
                        result.grid[x][y] += current.grid[x][y];
                    }
                }
            }

            OutputStream os = new BufferedOutputStream(new FileOutputStream(args[args.length - 1]));
            result.write(os);
            os.close();

            os = new BufferedOutputStream(new FileOutputStream(args[args.length - 1] + ".png"));
            result.writePng(os);
            os.close();
        } else if ("mask".equals(args[0])) {
            InputStream is = new BufferedInputStream(new FileInputStream(args[1]));
            Grid grid = Grid.read(is);
            is.close();

            // read the geojson
            GeoJsonFeatureCollection features =
                    JsonUtilities.lenientObjectMapper.readValue(new File(args[2]), GeoJsonFeatureCollection.class);

            if (features.features.size() != 1) {
                LOG.error("GeoJSON mask must have exactly one feature!");
                System.exit(1);
            }

            Geometry geom = features.features.iterator().next().getGeometry();
            if (!Polygonal.class.isInstance(geom)) {
                LOG.error("GeoJSON mask must be a polygon or multipolygon!");
                System.exit(1);
            }

            grid.mask(geom, false);

            OutputStream os = new BufferedOutputStream(new FileOutputStream(args[3]));
            grid.write(os);
            os.close();

            os = new BufferedOutputStream(new FileOutputStream(args[3] + ".png"));
            grid.writePng(os);
            os.close();
        } else if ("crop".equals(args[0])) {
            InputStream is = new BufferedInputStream(new FileInputStream(args[1]));
            Grid grid = Grid.read(is);
            is.close();

            is = new BufferedInputStream(new FileInputStream(args[2]));
            Grid cropGrid = Grid.read(is);
            is.close();

            Grid cropped = grid.crop(cropGrid);

            OutputStream os = new BufferedOutputStream(new FileOutputStream(args[3]));
            cropped.write(os);
            os.close();

            os = new BufferedOutputStream(new FileOutputStream(args[3] + ".png"));
            cropped.writePng(os);
            os.close();
        }
    }

    /** Clear all pixels that do not fall inside the mask. If invert is true, clears all pixel that do fall within the mask */
    private void mask(Geometry mask, boolean invert) {
        for (int x = 0; x < width; x++) {
            int worldx = x + west;
            double longitude = pixelToLon(worldx, zoom);

            for (int y = 0; y < height; y++) {
                int worldy = y + north;
                double latitude = pixelToLat(worldy, zoom);

                Coordinate coord = new Coordinate(longitude, latitude);
                // TODO do we have to make a point here?
                // might it be more efficient to rasterize the geometry?
                // ^ is xor, if we're inverting, the contains needs to be true,
                // otherwise false
                if (invert ^ !mask.contains(GeometryUtils.geometryFactory.createPoint(coord))) {
                    this.grid[x][y] = 0;
                }
            }
        }
    }

    /** Clip this grid to be the same size as another grid */
    public Grid crop (Grid other) {
        if (other.zoom != zoom) {
            throw new IllegalArgumentException("Zooms do not match!");
        }

        if (other.west < west ||
                other.north < north ||
                other.west + other.width > west + width ||
                other.north + other.height > north + height) {
            throw new IllegalArgumentException("Grid must be contained within this grid!");
        }

        Grid newGrid = new Grid(zoom, other.width, other.height, other.north, other.west);

        int fromx = other.west - this.west;
        int fromy = other.north - this.north;

        for (int inx = fromx, outx = 0; outx < other.width; outx++, inx++) {
            for (int iny = fromy, outy = 0; outy < other.height; outy++, iny++) {
                newGrid.grid[outx][outy] = grid[inx][iny];
            }
        }

        return newGrid;
    }

    /** Return true if this grid has the same zoom and bounds as the other grid */
    public boolean hasSameZoomAndBoundsAs(Grid o) {
        return zoom == o.zoom &&
                width == o.width &&
                height == o.height &&
                north == o.north &&
                west == o.west;
    }

}
