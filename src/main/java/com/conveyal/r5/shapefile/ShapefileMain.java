package com.conveyal.r5.shapefile;

import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.Way;
import com.esotericsoftware.minlog.Log;
import com.vividsolutions.jts.algorithm.LineIntersector;
import com.vividsolutions.jts.algorithm.RobustLineIntersector;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.MultiLineString;
import com.vividsolutions.jts.geom.PrecisionModel;
import com.vividsolutions.jts.index.SpatialIndex;
import com.vividsolutions.jts.index.quadtree.Quadtree;
import com.vividsolutions.jts.noding.IntersectionAdder;
import com.vividsolutions.jts.noding.MCIndexNoder;
import com.vividsolutions.jts.noding.NodedSegmentString;
import com.vividsolutions.jts.noding.Noder;
import com.vividsolutions.jts.noding.SegmentNode;
import com.vividsolutions.jts.noding.SegmentNodeList;
import com.vividsolutions.jts.noding.SegmentString;
import com.vividsolutions.jts.noding.SegmentStringUtil;
import com.vividsolutions.jts.operation.distance.DistanceOp;
import com.vividsolutions.jts.operation.distance.GeometryLocation;
import gnu.trove.list.TLongList;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.map.TObjectLongMap;
import gnu.trove.map.hash.TObjectLongHashMap;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ShapefileMain {

    private static final Logger LOG = LoggerFactory.getLogger(ShapefileMain.class);

    // at line crossings within layers, and between layers
    private boolean intersect = false;

    private long nextTemporaryOsmWayId = -1;

    private OSM osm;

    private SpatialIndex wayIndex = new Quadtree();

    /**
     * Given N shapefiles and/or OSM files, merge them all, creating new nodes as requested.
     *
     * Inputs can really be anything that amounts to a collection of linestring features.
     *
     * JTS and Geotools probably have methods for a lot of operations.
     */
    public static void main (String[] args) throws Throwable {
        new ShapefileMain().run();
    }

    private void run () throws Throwable {

        osm = new OSM(null);
        // Can pre-load OSM data here:
        // osm.readFromFile(x);

        // TODO Pre-initialize nodeDeduplicator with OSM nodes from any OSM inputs.

//        // Base network. LTS attributes are: LTSV2 LTSDV2 MVILTS MVIVLTS LTS_ACC
//        loadShapefile("/Users/abyrd/geodata/bogota/ltsnets/Red_LTS_DPr.shp", "LTSV2");
//
//        // Switch on intersection creation here - within the layer itself and with any pre-existing ways.
//        intersect = true;
//
//        // Cycleways. LTS attributes are LTS LTSD RCiLTS RCiFLTS MVILTS MVINCLTS
//        loadShapefile("/Users/abyrd/geodata/bogota/ltsnets/Ciclovia_LTS_D.shp", "LTS");

        // Cycleways. LTS attributes are LTS LTSD RCiLTS RCiFLTS MVILTS MVINCLTS
        loadShapefileIntoSegmentStrings("/Users/abyrd/geodata/bogota/ltsnets/Ciclovia_LTS_D.shp", "LTS");
        // Base network. LTS attributes are: LTSV2 LTSDV2 MVILTS MVIVLTS LTS_ACC
        loadShapefileIntoSegmentStrings("/Users/abyrd/geodata/bogota/ltsnets/Red_LTS_DPr.shp", "LTSV2");
        indexNodingWithPrecision();

        osm.writeToFile("output.osm.pbf");

    }

    private void loadShapefile (String filename, String ltsAttributeName) throws Throwable {

        final File file = new File(filename);
        LOG.info("Loading Shapefile {}", file);

        // Open shapefile
        Map<String, Object> map = new HashMap<>();
        map.put("url", file.toURI().toURL());

        DataStore dataStore = DataStoreFinder.getDataStore(map);
        String typeName = dataStore.getTypeNames()[0];

        FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore.getFeatureSource(typeName);
        Filter filter = Filter.INCLUDE;
        // ECQL.toFilter("BBOX(THE_GEOM, 10,20,30,40)");

        FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures(filter);

        // Find transform from shapefile coordinate system into WGS84
        CoordinateReferenceSystem sourceCrs = collection.getSchema().getCoordinateReferenceSystem();
        transform = CRS.findMathTransform(sourceCrs, DefaultGeographicCRS.WGS84, true);

        try (FeatureIterator<SimpleFeature> features = collection.features()) {
            int ltsTagCount = 0;
            while (features.hasNext()) {
                SimpleFeature feature = features.next();
                // System.out.print(feature.getID());
                // System.out.print(": ");
                // System.out.println(feature.getDefaultGeometryProperty().getValue());
                Object geometry = feature.getDefaultGeometry();
                // Unwrap MultiLinestrings into a list of LineStrings
                List<LineString> lineStrings = new ArrayList<>();
                if (geometry instanceof LineString) {
                    lineStrings.add((LineString)geometry);
                } else if (geometry instanceof MultiLineString) {
                    int nLineStrings = ((MultiLineString) geometry).getNumGeometries();
                    for (int i = 0; i < nLineStrings; i++) {
                        lineStrings.add((LineString)((MultiLineString) geometry).getGeometryN(i));
                    }
                } else {
                    throw new RuntimeException("Unsupported geometry type: " + geometry.getClass());
                }
                Object lts = feature.getAttribute(ltsAttributeName);
                for (LineString lineString : lineStrings) {
                    TLongList nodesInWay = new TLongArrayList();
                    for (Coordinate sourceCoordinate : lineString.getCoordinates()) {
                        // Perform rounding in source CRS which is probably in isotropic meters
                        long nodeId = getNodeForBin(sourceCoordinate.x, sourceCoordinate.y);
                        nodesInWay.add(nodeId);
                    }
                    Way way = new Way();
                    way.addTag("highway", "tertiary");
                    if (lts != null) {
                        way.addTag("lts", lts.toString());
                        ltsTagCount += 1;
                    }
                    LOG.info("tags: {}", way.tags);
                    way.nodes = nodesInWay.toArray();
                    long wayId = nextTemporaryOsmWayId;
                    nextTemporaryOsmWayId -= 1;
                    osm.ways.put(wayId, way);
                    WayLineString newWayLineString = new WayLineString(way, wayId, lineString);
                    if (intersect) {
                        List<WayLineString> oldWayLineStrings = wayIndex.query(lineString.getEnvelopeInternal());
                        for (WayLineString oldWayLineString : oldWayLineStrings) {
//                            Geometry intersection = wayLineString.lineString.intersection(lineString);
//                            if (!intersection.isEmpty()) {
//                                LOG.info("Intersection: {}", intersection);
//                            }
                            DistanceOp distanceOp = new DistanceOp(lineString, oldWayLineString.lineString, 1);
                            if (distanceOp.distance() < 2) {
                                GeometryLocation[] geometryLocations = distanceOp.nearestLocations();
                                insertOsmNode(newWayLineString, geometryLocations[0]);
                                insertOsmNode(oldWayLineString, geometryLocations[1]);
                            }
                        }
                    }
                    wayIndex.insert(lineString.getEnvelopeInternal(), newWayLineString);
                }
            }
            LOG.info("Total LTS tags added to OSM: {}", ltsTagCount);
        }
        dataStore.dispose();
    }


    private void insertOsmNode (WayLineString wayLineString, GeometryLocation geometryLocation) {
        Coordinate coordinate = geometryLocation.getCoordinate();
        long intersectionNodeId = getNodeForBin(coordinate.x, coordinate.y);
        // Insert node in list of nodes belonging to way
        int insertionIndex = geometryLocation.getSegmentIndex();
        int dst = 0;
        long[] oldNodes = wayLineString.way.nodes;
        long[] newNodes = new long[oldNodes.length + 1];
        if (oldNodes[insertionIndex] == intersectionNodeId || oldNodes[insertionIndex + 1] == intersectionNodeId) {
            // If inserting the new node ID would result in a repeating node, don't take any action.
            return;
        }
        for (int src = 0; src < oldNodes.length; src++, dst++) {
            newNodes[dst] = oldNodes[src];
            if (dst == insertionIndex) {
                dst += 1;
                newNodes[dst] = intersectionNodeId;
            }
        }
        wayLineString.way.nodes = newNodes;
        osm.ways.put(wayLineString.wayId, wayLineString.way);
    }

    /**
     * Associates an OSM way with its JTS Geometry to allow performing intersections.
     */
    private static class WayLineString {
        Way way;
        long wayId;
        LineString lineString;

        public WayLineString (Way way, long wayId, LineString lineString) {
            this.way = way;
            this.wayId = wayId;
            this.lineString = lineString;
        }
    }

    private List<NodedSegmentString> allSegmentStrings = new ArrayList<>();

    private MathTransform transform;

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
        transform = CRS.findMathTransform(sourceCrs, DefaultGeographicCRS.WGS84, true);

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

    public void indexNodingWithPrecision () throws Throwable {
        PrecisionModel fixedPM = new PrecisionModel(1);
        LineIntersector li = new RobustLineIntersector();
        li.setPrecisionModel(fixedPM);
        Noder noder = new MCIndexNoder(new IntersectionAdder(li));
        // This call should modify the segment strings in place, inserting new nodes.
        noder.computeNodes(allSegmentStrings);
        for (NodedSegmentString segmentString : allSegmentStrings) {
            // SegmentNodeList segmentNodeList = segmentString.getNodeList(); // This gets only the nodes (intersections with other segmentstrings)
            TLongList nodesInWay = new TLongArrayList();
            for (Coordinate sourceCoordinate : segmentString.getCoordinates()) {
                // Perform rounding in source CRS which is probably in isotropic meters
                long osmNodeId = getNodeForBin(sourceCoordinate.x, sourceCoordinate.y);
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


    /// DEDUPLICATE OSM NODES

    TObjectLongMap<BinKey> osmNodeForBin = new TObjectLongHashMap<>();

    private long nextTempOsmId = -1;

    private static final double roundingMultiplier = 1;

    /**
     * Lazy-create.
     * Stores any newly created nodes in the OSM MapDB.
     */
    private long getNodeForBin (double x, double y) {
        BinKey binKey = new BinKey(x, y);
        long nodeId = osmNodeForBin.get(binKey);
        if (nodeId == 0) {
            nodeId = nextTempOsmId;
            nextTempOsmId -= 1;
            osmNodeForBin.put(binKey, nodeId);
            try {
                Coordinate wgsCoordinate = new Coordinate();
                JTS.transform(new Coordinate(x, y), wgsCoordinate, transform);
                osm.nodes.put(nodeId, new Node(wgsCoordinate.y, wgsCoordinate.x));
            } catch (TransformException e) {
                throw new RuntimeException(e);
            }
        }
        return nodeId;
    }

    private static class BinKey {

        private int xBin;
        private int yBin;

        public BinKey (double x, double y) {
            this.xBin = (int)(x * roundingMultiplier);
            this.yBin = (int)(y * roundingMultiplier);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BinKey key = (BinKey) o;
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
