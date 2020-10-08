package com.conveyal.r5.shapefile;

import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.Way;
import gnu.trove.list.TLongList;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.map.TObjectLongMap;
import gnu.trove.map.hash.TObjectLongHashMap;
import org.apache.commons.math3.util.FastMath;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.algorithm.LineIntersector;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.noding.IntersectionAdder;
import org.locationtech.jts.noding.MCIndexNoder;
import org.locationtech.jts.noding.NodedSegmentString;
import org.locationtech.jts.noding.Noder;
import org.locationtech.jts.noding.SegmentString;
import org.locationtech.jts.noding.SegmentStringUtil;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * This Main class serves as a tool to convert Shapefiles into OSM data, which can then be imported into Analysis.
 * The initial use case is for performing accessibility analysis on shapefiles representing bicycle networks.
 * These shapefiles have a property containing the Level of Traffic Stress (LTS) for each road segment.
 * This property is converted to an OSM tag in the output data. R5 reads and uses this tag to set its LTS values.
 * Several shapefiles are loaded and "noded", i.e. topological connections are created everywhere lines cross.
 * The inputs could potentially be any format that amounts to a collection of linestring features (GeoJSON etc.).
 */
public class ShapefileMain {

    private static final Logger LOG = LoggerFactory.getLogger(ShapefileMain.class);

    /**
     * We use negative OSM way and node IDs, decrementing for each new entity that is created.
     * This is the convention for entities that don't appear in the shared global OSM database.
     */
    private long nextTemporaryOsmWayId = -1;

    /** While performing the conversion, OSM output is accumulated here and written out at the end. */
    private OSM osm;

    private List<NodedSegmentString> allSegmentStrings = new ArrayList<>();

    /** The transform from the input shapefile coordinate reference system into WGS84 latitude and longitude. */
    private MathTransform coordinateTransform;

    public static void main (String[] args) throws Throwable {
        new ShapefileMain().run();
    }

    private void run () throws Throwable {

        // OSM data store using temporary file.
        osm = new OSM(null);

        // We could pre-load OSM data here: osm.readFromFile(x);
        // Then we would need to pre-initialize the node deduplicator with OSM nodes from those OSM inputs.

        // Load cycleways. LTS attributes are LTS LTSD RCiLTS RCiFLTS MVILTS MVINCLTS
        loadShapefileIntoSegmentStrings("/Users/abyrd/geodata/bogota/ltsnets/Ciclovia_LTS_D.shp", "LTSD");

        // Load base network. LTS attributes are: LTSV2 LTSDV2 MVILTS MVIVLTS LTS_ACC
        loadShapefileIntoSegmentStrings("/Users/abyrd/geodata/bogota/ltsnets/Red_LTS_DPr.shp", "LTSDV1");

        extendSegmentStrings();

        performIndexNodingWithPrecision();

        osm.writeToFile("output.osm.pbf");

    }

    private void loadShapefileIntoSegmentStrings (String filename, String ltsAttributeName) throws Throwable {

        final File file = new File(filename);
        LOG.info("Loading Shapefile {}", file);

        // Open shapefile
        Map<String, Object> map = new HashMap<>();
        map.put("url", file.toURI().toURL());

        DataStore dataStore = DataStoreFinder.getDataStore(map);
        String typeName = dataStore.getTypeNames()[0];

        FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore.getFeatureSource(typeName);
        Filter filter = Filter.INCLUDE;
        FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures(filter);

        // Find transform from shapefile coordinate system into WGS84
        // Note that this assumes all input files are in the same CRS
        CoordinateReferenceSystem sourceCrs = collection.getSchema().getCoordinateReferenceSystem();
        coordinateTransform = CRS.findMathTransform(sourceCrs, DefaultGeographicCRS.WGS84, true);

        try (FeatureIterator<SimpleFeature> features = collection.features()) {
            int ltsTagCount = 0;
            while (features.hasNext()) {
                SimpleFeature feature = features.next();
                Object geometry = feature.getDefaultGeometry();
                Object lts = feature.getAttribute(ltsAttributeName);
                if (geometry != null && geometry instanceof Geometry) {
                    List<NodedSegmentString> segmentStrings = SegmentStringUtil.extractSegmentStrings((Geometry)geometry);
                    for (SegmentString segmentString : segmentStrings) {
                        segmentString.setData(lts);
                    }
                    allSegmentStrings.addAll(segmentStrings);
                }
            }
        }
        dataStore.dispose();
    }

    /**
     * Use the code provided by JTS for "noding", i.e. creating shared nodes at each place where shapes cross each other.
     */
    private void performIndexNodingWithPrecision () {

        PrecisionModel fixedPM = new PrecisionModel(1);
        LineIntersector li = new RobustLineIntersector();
        li.setPrecisionModel(fixedPM);
        Noder noder = new MCIndexNoder(new IntersectionAdder(li));
        // noder = new IteratedNoder(new PrecisionModel());
        noder.computeNodes(allSegmentStrings);

        // NB: based on experience, you must call getNodedSubstrings to splice the nodes into the coordinate list.
        // The input SegmentStrings are not fully modified in place.
        allSegmentStrings = (List) (noder.getNodedSubstrings());

        for (NodedSegmentString segmentString : allSegmentStrings) {
            TLongList nodesInWay = new TLongArrayList();
            for (Coordinate sourceCoordinate : segmentString.getCoordinates()) {
                // Perform rounding in source CRS which is should be in isotropic meters.
                long osmNodeId = getNodeForCoordinate(sourceCoordinate.x, sourceCoordinate.y);
                nodesInWay.add(osmNodeId);
            }
            Way way = new Way();
            way.addTag("highway", "tertiary");
            Object lts = segmentString.getData();
            if (lts != null) {
                way.addTag("lts", lts.toString());
            }
            way.nodes = nodesInWay.toArray();
            osm.ways.put(nextTemporaryOsmWayId, way);
            nextTemporaryOsmWayId -= 1;
        }
    }

    /**
     * The input data contains some paths that are intended to cross or connect to neighboring paths, but stop just a
     * little bit short of actually touching those neighboring paths. This method finds SegmentStrings that appear to
     * be such dead ends, extends them slightly, and reruns the noding operation to try to create more intersections.
     * This is a heuristic fix, and may create as many problems as it solves in places where there are true dead ends.
     *
     * Detection of dead ends via node-coordinate comparison seems to fail, so currently extending all shapes at both ends.
     * This is overkill but should work. It seems like the endpoints of every SegmentString are considered nodes.
     */
    private void extendSegmentStrings () {
        final double DIST_TO_EXTEND = 4; // source CRS units, usually meters.
        List<NodedSegmentString> outputSegmentStrings = new ArrayList<>();
        for (NodedSegmentString segmentString : allSegmentStrings) {
            Coordinate [] coordinates = segmentString.getCoordinates();
            // Remove repeated coordinates in the incoming geometries.
            // Repeated geometries lead to zero lengths, which lead to division by zero, yielding NaN coordinates which
            // then cascade an obscure error in the spatial index ("comparison violates its general contract").
            {
                List<Coordinate> nonDuplicateCoords = new ArrayList<>();
                Coordinate prevCoord = null;
                for (Coordinate currCoord : coordinates) {
                    if (prevCoord == null || !prevCoord.equals2D(currCoord)) {
                        nonDuplicateCoords.add(currCoord);
                    }
                    prevCoord = currCoord;
                }
                coordinates = nonDuplicateCoords.toArray(new Coordinate[nonDuplicateCoords.size()]);
            }
            // Drop fragments, possibly created by repeated coordinates in input data being split.
            if (coordinates.length < 2) {
                continue;
            }

            // Extend forward from end of linestring
            Coordinate lastCoord = coordinates[coordinates.length - 1];
            Coordinate secondToLastCoord = coordinates[coordinates.length - 2];
            coordinates = Arrays.copyOf(coordinates, coordinates.length + 1);
            coordinates[coordinates.length - 1] = extendLineSegment(secondToLastCoord, lastCoord, DIST_TO_EXTEND);

            // Extend backward from beginning of linestring
            Coordinate[] newCoordinates = new Coordinate[coordinates.length +1];
            System.arraycopy(coordinates, 0, newCoordinates, 1, coordinates.length);
            newCoordinates[0] = extendLineSegment(coordinates[1], coordinates[0], DIST_TO_EXTEND);
            coordinates = newCoordinates;

            // Create a new segment string with the processed coordinate and same LTS level as the source.
            outputSegmentStrings.add(new NodedSegmentString(coordinates, segmentString.getData()));
        }
        // Overwrite old segment strings with new extended ones.
        allSegmentStrings = outputSegmentStrings;
    }

    /**
     * Given two coordinates a and b, extend the line segment from a to b by the given distance, returning the new
     * end point.
     */
    private static Coordinate extendLineSegment (Coordinate a, Coordinate b, double distanceToExtend) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double length = FastMath.sqrt(dx * dx + dy * dy);
        if (length == 0) {
            LOG.error("Zero length segment.");
            return b;
        }
        Coordinate result = new Coordinate();
        result.x = b.x + dx / length * distanceToExtend;
        result.y = b.y + dy / length * distanceToExtend;
        return result;
    }

    /// CODE FOR DEDUPLICATING (MERGING) OSM NODES

    /** A map from rounded coordinate bins to the long ID for the merged OSM node that represents each bin. */
    TObjectLongMap<CoordBinKey> osmNodeForBin = new TObjectLongHashMap<>();

    /**
     * We use negative OSM way and node IDs, decrementing for each new entity that is created.
     * This is the convention for entities that don't appear in the shared global OSM database.
     */
    private long nextTempOsmId = -1;

    private static final double COORD_ROUNDING_MULTIPLIER = 1;

    /**
     * Lazy-create OSM node objects, storing the newly created nodes in the OSM MapDB.
     * If a node has already been requested for a location in the same N-meter bin, the pre-existing node is returned.
     */
    private long getNodeForCoordinate (double x, double y) {
        CoordBinKey binKey = new CoordBinKey(x, y);
        long nodeId = osmNodeForBin.get(binKey);
        if (nodeId == 0) {
            nodeId = nextTempOsmId;
            nextTempOsmId -= 1;
            osmNodeForBin.put(binKey, nodeId);
            try {
                Coordinate wgsCoordinate = new Coordinate();
                JTS.transform(new Coordinate(x, y), wgsCoordinate, coordinateTransform);
                osm.nodes.put(nodeId, new Node(wgsCoordinate.y, wgsCoordinate.x));
            } catch (TransformException e) {
                throw new RuntimeException(e);
            }
        }
        return nodeId;
    }

    /**
     * Objects of this class serve as keys for deduplicated / spatially merged OSM nodes. Floating point coordinates are
     * truncated to integers after being scaled by a multiplier. This essentially bins the nodes by geographic proximity.
     * Now that we are using the standard JTS noding utilities, this may not be necessary. JTS is using a similar
     * coordinate rounding approach to combine nodes, but I'm not sure that the rounded coordinates are being stored
     * This code was already written and using it reassures me that we are really merging into the same OSM node.
     */
    private static class CoordBinKey {

        private int xBin;
        private int yBin;

        public CoordBinKey (double x, double y) {
            this.xBin = (int)(x * COORD_ROUNDING_MULTIPLIER);
            this.yBin = (int)(y * COORD_ROUNDING_MULTIPLIER);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CoordBinKey key = (CoordBinKey) o;
            return xBin == key.xBin && yBin == key.yBin;
        }

        @Override
        public int hashCode() {
            return Objects.hash(xBin, yBin);
        }

        @Override
        public String toString() {
            return "(" + xBin + "," + yBin + ')';
        }

    }

}
