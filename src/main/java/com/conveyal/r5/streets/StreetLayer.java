package com.conveyal.r5.streets;

import com.conveyal.gtfs.model.Stop;
import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.Way;
import com.conveyal.r5.api.util.BikeRentalStation;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.labeling.*;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.vividsolutions.jts.geom.Envelope;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TLongIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import com.conveyal.r5.transit.TransitLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.Serializable;
import java.util.*;

/**
 * This stores the street layer of OTP routing data.
 *
 * Is is currently using a column store.
 * An advantage of disk-backing this (FSTStructs, MapDB optimized for zero-based
 * integer keys) would be that we can remove any logic about loading/unloading graphs.
 * We could route over a whole continent without using much memory.
 *
 * Any data that's not used by Analyst workers (street names and geometries for example)
 * should be optional so we can have fast-loading, small transportation network files to pass around.
 * It can even be loaded from the OSM MapDB on demand.
 *
 * There's also https://github.com/RichardWarburton/slab
 * which seems simpler to use.
 *
 * TODO Morton-code-sort vertices, then sort edges by from-vertex.
 */
public class StreetLayer implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(StreetLayer.class);

    /**
     * Minimum allowable size (in number of vertices) for a disconnected subgraph; subgraphs smaller than these will be removed.
     * There are several reasons why one might have a disconnected subgraph. The most common is poor quality
     * OSM data. However, they also could be due to areas that really are disconnected in the street graph,
     * and are connected only by transit. These could be literal islands (Vashon Island near Seattle comes
     * to mind), or islands that are isolated by infrastructure (for example, airport terminals reachable
     * only by transit or driving, for instance BWI or SFO).
     */
    public static final int MIN_SUBGRAPH_SIZE = 40;

    private static final int SNAP_RADIUS_MM = 5 * 1000;

    // Edge lists should be constructed after the fact from edges. This minimizes serialized size too.
    public transient List<TIntList> outgoingEdges;
    public transient List<TIntList> incomingEdges;
    public transient IntHashGrid spatialIndex = new IntHashGrid();
    //Key is street vertex ID value is BikeRentalStation (with name, number of bikes, spaces id etc.)
    public TIntObjectMap<BikeRentalStation> bikeRentalStationMap;

    private transient TraversalPermissionLabeler permissions = new USTraversalPermissionLabeler(); // TODO don't hardwire to US
    private transient LevelOfTrafficStressLabeler stressLabeler = new LevelOfTrafficStressLabeler();
    private transient TypeOfEdgeLabeler typeOfEdgeLabeler = new TypeOfEdgeLabeler();
    private transient SpeedConfigurator speedConfigurator;

    /** Envelope of this street layer, in decimal degrees (non-fixed-point) */
    public Envelope envelope = new Envelope();

    TLongIntMap vertexIndexForOsmNode = new TLongIntHashMap(100_000, 0.75f, -1, -1);
    // TIntLongMap osmWayForEdgeIndex;

    // TODO use negative IDs for temp vertices and edges.

    // This is only used when loading from OSM, and is then nulled to save memory.
    transient OSM osm;

    // Initialize these when we have an estimate of the number of expected edges.
    public VertexStore vertexStore = new VertexStore(100_000);
    public EdgeStore edgeStore = new EdgeStore(vertexStore, 200_000);

    transient Histogram edgesPerWayHistogram = new Histogram("Number of edges per way per direction");
    transient Histogram pointsPerEdgeHistogram = new Histogram("Number of geometry points per edge");

    public TransitLayer linkedTransitLayer = null;

    public static final EnumSet<EdgeStore.EdgeFlag> ALL_PERMISSIONS = EnumSet
        .of(EdgeStore.EdgeFlag.ALLOWS_BIKE, EdgeStore.EdgeFlag.ALLOWS_CAR,
            EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.NO_THRU_TRAFFIC,
            EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_BIKE, EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_PEDESTRIAN,
            EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR);

    public boolean bikeSharing = false;

    public StreetLayer(TNBuilderConfig tnBuilderConfig) {
            speedConfigurator = new SpeedConfigurator(tnBuilderConfig.speeds);
    }

    /** Load street layer from an OSM-lib OSM DB */
    public void loadFromOsm(OSM osm) {
        loadFromOsm(osm, true, false);
    }

    /**
     * Returns true if way can be used for routing
     *
     * Routable ways are highways (unless they are raceways or highway rest_area/services since those are similar to landuse tags
     * Or public_transport platform or railway platform unless its usage tag is tourism
     *
     * In both cases roads need to exists in reality aka don't have:
     * - construction,
     * - proposed,
     * - removed,
     * - abandoned
     * - unbuilt
     * tags
     *
     * Both construction tagging schemes are supported tag construction=anything and highway/cycleway=construction
     * same with proposed.
     * @param way
     * @return
     */
    private static boolean isWayRoutable(Way way) {
        boolean isRoutable = false;

        String highway = way.getTag("highway");

        if (
            //Way is routable if it is highway
            (way.hasTag("highway") && !(
                //Unless it is raceway or rest area
                //Those two are areas which are places around highway (similar to landuse tags they aren't routable)
                highway.equals("services") || highway.equals("rest_area")
                //highway=conveyor is obsoleted tag for escalator and is actually routable
                || highway.equals("raceway")))
                //or it is public transport platform or railway platform
            || (way.hasTag("public_transport", "platform")
                || way.hasTag("railway", "platform")
                //unless it's usage is tourism
                && !way.hasTag("usage", "tourism"))) {

            isRoutable = actuallyExistsInReality(highway, way);

        }

        if (isRoutable && way.hasTag("cycleway")) {
            //highway tag is already checked
            String cycleway = way.getTag("cycleway");
            isRoutable = actuallyExistsInReality(cycleway, way);
        }

        return isRoutable;
    }

    /**
     * Returns true if road is not in construction, abandoned, removed or proposed
     * @param highway value of highway or cycleway tag
     * @param way
     * @return
     */
    private static boolean actuallyExistsInReality(String highway, Way way) {
        return !("construction".equals(highway)
            || "abandoned".equals(highway)|| "removed".equals(highway)
            || "proposed".equals(highway) || "propossed".equals(highway)
            || "unbuilt".equals(highway)
            || way.hasTag("construction") || way.hasTag("proposed"));
    }


    /** Load OSM, optionally removing floating subgraphs (recommended) */
    void loadFromOsm (OSM osm, boolean removeIslands, boolean saveVertexIndex) {
        if (!osm.intersectionDetection)
            throw new IllegalArgumentException("Intersection detection not enabled on OSM source");

        LOG.info("Making street edges from OSM ways...");
        this.osm = osm;
        for (Map.Entry<Long, Way> entry : osm.ways.entrySet()) {
            Way way = entry.getValue();
            if (!isWayRoutable(way)) {
                continue;
            }
            int nEdgesCreated = 0;
            int beginIdx = 0;
            // Break each OSM way into topological segments between intersections, and make one edge per segment.
            for (int n = 1; n < way.nodes.length; n++) {
                if (osm.intersectionNodes.contains(way.nodes[n]) || n == (way.nodes.length - 1)) {
                    makeEdge(way, beginIdx, n, entry.getKey());
                    nEdgesCreated += 1;
                    beginIdx = n;
                }
            }
            edgesPerWayHistogram.add(nEdgesCreated);
        }
        LOG.info("Done making street edges.");
        LOG.info("Made {} vertices and {} edges.", vertexStore.nVertices, edgeStore.nEdges);

        // need edge lists to apply intersection costs
        buildEdgeLists();
        stressLabeler.applyIntersectionCosts(this);

        if (removeIslands)
            removeDisconnectedSubgraphs(MIN_SUBGRAPH_SIZE);

        //edgesPerWayHistogram.display();
        //pointsPerEdgeHistogram.display();
        // Clear unneeded indexes, allow them to be gc'ed
        if (!saveVertexIndex)
            vertexIndexForOsmNode = null;

        osm = null;
    }

    public void openOSM(File file) {
        osm = new OSM(file.getPath());
        LOG.info("Read OSM");
    }

    /**
     * Gets way name from OSM name tag
     *
     * It uses OSM Mapdb
     *
     * Uses {@link #getName(long, Locale)}
     *
     * @param edgeIdx edgeStore EdgeIDX
     * @param locale which locale to use
     * @return null if edge doesn't have name tag or if OSM data isn't loaded
     */
    public String getNameEdgeIdx(int edgeIdx, Locale locale) {
        if (osm == null) {
            return null;
        }
        EdgeStore.Edge edge = edgeStore.getCursor(edgeIdx);
        return getName(edge.getOSMID(), locale);
    }

    /**
     * Gets way name from OSM name tag
     *
     * TODO: generate name on unnamed ways (sidewalks, cycleways etc.)
     * @param OSMid OSM ID of a way
     * @param locale which locale to use
     * @return
     */
    private String getName(long OSMid, Locale locale) {
        String name = null;
        Way way = osm.ways.get(OSMid);
        if (way != null) {
            name = way.getTag("name");
        }
        return name;
    }

    /**
     * Get or create mapping from a global long OSM ID to an internal street vertex ID, creating the vertex as needed.
     */
    private int getVertexIndexForOsmNode(long osmNodeId) {
        int vertexIndex = vertexIndexForOsmNode.get(osmNodeId);
        if (vertexIndex == -1) {
            // Register a new vertex, incrementing the index starting from zero.
            // Store node coordinates for this new street vertex
            Node node = osm.nodes.get(osmNodeId);
            vertexIndex = vertexStore.addVertex(node.getLat(), node.getLon());

            VertexStore.Vertex v = vertexStore.getCursor(vertexIndex);
            if (node.hasTag("highway", "traffic_signals"))
                v.setFlag(VertexStore.VertexFlag.TRAFFIC_SIGNAL);

            vertexIndexForOsmNode.put(osmNodeId, vertexIndex);
        }
        return vertexIndex;
    }

    /**
     * Calculate length from a list of nodes. This is done in advance of creating an edge pair because we need to catch
     * potential length overflows before we ever reserve space for the edges.
     */
    private int getEdgeLengthMillimeters (List<Node> nodes) {
        double lengthMeters = 0;
        Node prevNode = nodes.get(0);
        for (Node node : nodes.subList(1, nodes.size())) {
            lengthMeters += GeometryUtils
                    .distance(prevNode.getLat(), prevNode.getLon(), node.getLat(), node.getLon());
            prevNode = node;
        }
        if (lengthMeters * 1000 > Integer.MAX_VALUE) {
            return -1;
        }
        return (int)(lengthMeters * 1000);
    }

    private static short speedToShort(Float speed) {
        return (short) Math.round(speed * 100);
    }

    /**
     * Make an edge for a sub-section of an OSM way, typically between two intersections or dead ends.
     */
    private void makeEdge(Way way, int beginIdx, int endIdx, Long osmID) {

        long beginOsmNodeId = way.nodes[beginIdx];
        long endOsmNodeId = way.nodes[endIdx];

        // Will create mapping if it doesn't exist yet.
        int beginVertexIndex = getVertexIndexForOsmNode(beginOsmNodeId);
        int endVertexIndex = getVertexIndexForOsmNode(endOsmNodeId);

        // Fetch the OSM node objects for this subsection of the OSM way.
        int nNodes = endIdx - beginIdx + 1;
        List<Node> nodes = new ArrayList<>(nNodes);
        for (int n = beginIdx; n <= endIdx; n++) {
            long nodeId = way.nodes[n];
            Node node = osm.nodes.get(nodeId);
            envelope.expandToInclude(node.getLon(), node.getLat());
            nodes.add(node);
        }

        // Compute edge length and check that it can be properly represented.
        int edgeLengthMillimeters = getEdgeLengthMillimeters(nodes);
        if (edgeLengthMillimeters < 0) {
            LOG.warn("Street segment was too long to be represented, skipping.");
            return;
        }

        // FIXME this encoded speed should probably never be exposed outside the edge object
        short forwardSpeed = speedToShort(speedConfigurator.getSpeedMS(way, false));
        short backwardSpeed = speedToShort(speedConfigurator.getSpeedMS(way, true));

        RoadPermission roadPermission = permissions.getPermissions(way);

        // Create and store the forward and backward edge
        // FIXME these sets of flags should probably not leak outside the permissions/stress/etc. labeler methods
        EnumSet<EdgeStore.EdgeFlag> forwardFlags = roadPermission.forward;
        EnumSet<EdgeStore.EdgeFlag> backFlags = roadPermission.backward;

        // Doesn't insert edges which don't have any permissions forward and backward
        if (Collections.disjoint(forwardFlags, ALL_PERMISSIONS) && Collections.disjoint(backFlags, ALL_PERMISSIONS)) {
            LOG.debug("Way has no permissions skipping!");
            return;
        }

        stressLabeler.label(way, forwardFlags, backFlags);

        typeOfEdgeLabeler.label(way, forwardFlags, backFlags);

        EdgeStore.Edge newEdge = edgeStore.addStreetPair(beginVertexIndex, endVertexIndex, edgeLengthMillimeters, osmID);
        // newEdge is first pointing to the forward edge in the pair.
        // Geometries apply to both edges in a pair.
        newEdge.setGeometry(nodes);
        newEdge.setFlags(forwardFlags);
        newEdge.setSpeed(forwardSpeed);
        // Step ahead to the backward edge in the same pair.
        newEdge.advance();
        newEdge.setFlags(backFlags);
        newEdge.setSpeed(backwardSpeed);

        pointsPerEdgeHistogram.add(nNodes);
    }

    public void indexStreets () {
        LOG.info("Indexing streets...");
        spatialIndex = new IntHashGrid();
        // Skip by twos, we only need to index forward (even) edges. Their odd companions have the same geometry.
        EdgeStore.Edge edge = edgeStore.getCursor();
        for (int e = 0; e < edgeStore.nEdges; e += 2) {
            edge.seek(e);
            // FIXME for now we are skipping edges over 1km because they can have huge envelopes. TODO Rasterize them.
            //if (edge.getLengthMm() < 1 * 1000 * 1000) {
            spatialIndex.insert(edge.getEnvelope(), e);
            //}
        }
        LOG.info("Done indexing streets.");
    }

    /** After JIT this appears to scale almost linearly with number of cores. */
    public void testRouting (boolean withDestinations, TransitLayer transitLayer) {
        LOG.info("Routing from random vertices in the graph...");
        LOG.info("{} goal direction.", withDestinations ? "Using" : "Not using");
        StreetRouter router = new StreetRouter(this);
        long startTime = System.currentTimeMillis();
        final int N = 1_000;
        final int nVertices = outgoingEdges.size();
        Random random = new Random();
        for (int n = 0; n < N; n++) {
            int from = random.nextInt(nVertices);
            VertexStore.Vertex vertex = vertexStore.getCursor(from);
            // LOG.info("Routing from ({}, {}).", vertex.getLat(), vertex.getLon());
            router.setOrigin(from);
            router.toVertex = withDestinations ? random.nextInt(nVertices) : StreetRouter.ALL_VERTICES;
            if (n != 0 && n % 100 == 0) {
                LOG.info("    {}/{} searches", n, N);
            }
        }
        double eTime = System.currentTimeMillis() - startTime;
        LOG.info("average response time {} msec", eTime / N);
    }

    public void buildEdgeLists() {
        LOG.info("Building edge lists from edges...");
        outgoingEdges = new ArrayList<>(vertexStore.nVertices);
        incomingEdges = new ArrayList<>(vertexStore.nVertices);
        for (int v = 0; v < vertexStore.nVertices; v++) {
            outgoingEdges.add(new TIntArrayList(4));
            incomingEdges.add(new TIntArrayList(4));
        }
        EdgeStore.Edge edge = edgeStore.getCursor();
        while (edge.advance()) {
            outgoingEdges.get(edge.getFromVertex()).add(edge.edgeIndex);
            incomingEdges.get(edge.getToVertex()).add(edge.edgeIndex);
        }
        LOG.info("Done building edge lists.");
        // Display histogram of edge list sizes
        Histogram edgesPerListHistogram = new Histogram("Number of edges per edge list");
        for (TIntList edgeList : outgoingEdges) {
            edgesPerListHistogram.add(edgeList.size());
        }
        for (TIntList edgeList : incomingEdges) {
            edgesPerListHistogram.add(edgeList.size());
        }
        edgesPerListHistogram.display();
    }

    /**
     * Create a street-layer vertex representing a transit stop.
     * Connect that new vertex to the street network if possible.
     * The vertex will be created and assigned an index whether or not it is successfully linked.
     *
     * This uses {@link #findSplit(double, double, double)} and {@link Split} which need filled spatialIndex
     * In other works {@link #indexStreets()} needs to be called before this is used. Otherwise no near vertex is found.
     *
     * TODO maybe use X and Y everywhere for fixed point, and lat/lon for double precision degrees.
     * TODO move this into Split.perform(), store streetLayer ref in Split
     *
     * @return the index of a street vertex very close to this stop, or -1 if no such vertex could be found or created.
     */
    public int getOrCreateVertexNear (double lat, double lon, double radiusMeters) {

        Split split = findSplit(lat, lon, radiusMeters);
        if (split == null) {
            // If no linking site was found within range.
            return -1;
        }

        // We have a linking site. Find or make a suitable vertex at that site.
        // Retaining the original Edge cursor object inside findSplit is not necessary, one object creation is harmless.
        EdgeStore.Edge edge = edgeStore.getCursor(split.edge);

        // Check for cases where we don't need to create a new vertex (the edge is reached end-wise)
        if (split.distance0_mm < SNAP_RADIUS_MM || split.distance1_mm < SNAP_RADIUS_MM) {
            if (split.distance0_mm < split.distance1_mm) {
                // Very close to the beginning of the edge.
                return edge.getFromVertex();
            } else {
                // Very close to the end of the edge.
                return edge.getToVertex();
            }
        }

        // The split is somewhere away from an existing intersection vertex. Make a new vertex.
        int newVertexIndex = vertexStore.addVertexFixed((int)split.fLat, (int)split.fLon);

        // Modify the existing bidirectional edge pair to lead up to the split.
        // Its spatial index entry is still valid, its envelope has only shrunk.
        int oldToVertex = edge.getToVertex();
        edge.setLengthMm(split.distance0_mm);
        edge.setToVertex(newVertexIndex);
        edge.setGeometry(Collections.EMPTY_LIST); // Turn it into a straight line for now. FIXME split edges should have geometries

        // Make a second, new bidirectional edge pair after the split and add it to the spatial index.
        // New edges will be added to edge lists later (the edge list is a transient index).
        EdgeStore.Edge newEdge = edgeStore.addStreetPair(newVertexIndex, oldToVertex, split.distance1_mm, edge.getOSMID());
        spatialIndex.insert(newEdge.getEnvelope(), newEdge.edgeIndex);

        // Copy the flags and speeds for both directions, making the new edge like the existing one.
        newEdge.copyPairFlagsAndSpeeds(edge);

        return newVertexIndex;
        // TODO store street-to-stop distance in a table in TransitLayer. This also allows adjusting for subway entrances etc.
    }

    /**
     * Non-destructively find a location on an existing street near the given point.
     * PARAMETERS ARE FLOATING POINT GEOGRAPHIC (not fixed point ints)
     * @return a Split object representing a point along a sub-segment of a specific edge, or null if there are no streets nearby.
     */
    public Split findSplit (double lat, double lon, double radiusMeters) {
        return Split.find (lat, lon, radiusMeters, this);
    }


    /**
     * For every stop in a TransitLayer, find or create a nearby vertex in the street layer and record the connection
     * between the two.
     * It only makes sense to link one TransitLayer to one StreetLayer, otherwise the bi-mapping between transit stops
     * and street vertices would be ambiguous.
     */
    public void associateStops (TransitLayer transitLayer, int radiusMeters) {
        for (Stop stop : transitLayer.stopForIndex) {
            int streetVertexIndex = getOrCreateVertexNear(stop.stop_lat, stop.stop_lon, radiusMeters);
            transitLayer.streetVertexForStop.add(streetVertexIndex); // -1 means no link
            // The inverse stopForStreetVertex map is a transient, derived index and will be built later.
        }
        // Bidirectional reference between the StreetLayer and the TransitLayer
        transitLayer.linkedStreetLayer = this;
        this.linkedTransitLayer = transitLayer;
    }

    /**
     * Used to split streets for temporary endpoints and for transit stops.
     * transit: favor platforms and pedestrian paths, used in linking stops to streets
     * intoStreetLayer: the edges created by splitting a street are one-way. by default they are one-way out of the street
     * network, e.g. out to a transit stop or to the destination. When intoStreets is true, the split is performed such that
     * it leads into the street network instead of out of it. The fact that split edges are uni-directional is important
     * for a couple of reasons: it avoids using transit stops as shortcuts, and it makes temporary endpoint vertices
     * harmless to searches happening in other threads.
     */
    public void splitStreet(int fixedLon, int fixedLat, boolean transit, boolean out) {

    }

    public int getVertexCount() {
        return vertexStore.nVertices;
    }

    /**
     * Find and remove all subgraphs with fewer than minSubgraphSize vertices. Uses a flood fill
     * algorithm, see http://stackoverflow.com/questions/1348783.
     */
    public void removeDisconnectedSubgraphs(int minSubgraphSize) {
        LOG.info("Removing subgraphs with fewer than {} vertices", minSubgraphSize);
        boolean edgeListsBuilt = incomingEdges != null;

        int nSubgraphs = 0;

        if (!edgeListsBuilt)
            buildEdgeLists();

        // labels for the flood fill algorithm
        TIntIntMap vertexLabels = new TIntIntHashMap();

        // vertices and edges that should be removed
        TIntSet verticesToRemove = new TIntHashSet();
        TIntSet edgesToRemove = new TIntHashSet();

        for (int vertex = 0; vertex < vertexStore.nVertices; vertex++) {
            // N.B. this is not actually running a search for every vertex as after the first few
            // almost all of the vertices are labeled
            if (vertexLabels.containsKey(vertex))
                continue;

            StreetRouter r = new StreetRouter(this);
            r.setOrigin(vertex);
            // walk to the end of the graph
            r.distanceLimitMeters = Integer.MAX_VALUE;
            r.route();

            TIntList reachedVertices = new TIntArrayList();

            int nReached = 0;
            for (int reachedVertex = 0; reachedVertex < vertexStore.nVertices; reachedVertex++) {
                if (r.getTravelTimeToVertex(reachedVertex) != Integer.MAX_VALUE) {
                    nReached++;
                    // use source vertex as label, saves a variable
                    vertexLabels.put(reachedVertex, vertex);
                    reachedVertices.add(reachedVertex);
                }
            }

            if (nReached < minSubgraphSize) {
                nSubgraphs++;
                verticesToRemove.addAll(reachedVertices);
                reachedVertices.forEach(v -> {
                    // can't use method reference here because we always have to return true
                    incomingEdges.get(v).forEach(e -> {
                        edgesToRemove.add(e);
                        return true; // continue iteration
                    });
                    outgoingEdges.get(v).forEach(e -> {
                        edgesToRemove.add(e);
                        return true; // continue iteration
                    });
                    return true; // iteration should continue
                });
            }
        }

        // rebuild the edge store with some edges removed
        edgeStore.remove(edgesToRemove.toArray());
        // TODO remove vertices as well? this is messy because the edges point into them

        // don't forget this
        if (edgeListsBuilt) {
            buildEdgeLists();
            indexStreets();
        }
        else {
            incomingEdges = null;
            outgoingEdges = null;
        }

        if (nSubgraphs > 0)
            LOG.info("Removed {} disconnected subgraphs", nSubgraphs);
        else
            LOG.info("Found no subgraphs to remove, congratulations for having clean OSM data.");
        LOG.info("Done removing subgraphs. {} edges remain", edgeStore.nEdges);
    }

    public Envelope getEnvelope() {
        return envelope;
    }

    public List<TIntList> getOutgoingEdges() {
        return outgoingEdges;
    }

    public void associateBikeSharing(TNBuilderConfig tnBuilderConfig, int radiusMeters) {
        LOG.info("Builder file:{}", tnBuilderConfig.bikeRentalFile);
        BikeRentalBuilder bikeRentalBuilder = new BikeRentalBuilder(new File(tnBuilderConfig.bikeRentalFile));
        List<BikeRentalStation> bikeRentalStations = bikeRentalBuilder.getRentalStations();
        bikeRentalStationMap = new TIntObjectHashMap<>(bikeRentalStations.size());
        LOG.info("Bike rental stations:{}", bikeRentalStations.size());
        int numAddedStations = 0;
        for (BikeRentalStation bikeRentalStation: bikeRentalStations) {
            int streetVertexIndex = getOrCreateVertexNear(bikeRentalStation.lat, bikeRentalStation.lon, radiusMeters);
            if (streetVertexIndex > -1) {
                numAddedStations++;
                VertexStore.Vertex vertex = vertexStore.getCursor(streetVertexIndex);
                vertex.setFlag(VertexStore.VertexFlag.BIKE_SHARING);
                bikeRentalStationMap.put(streetVertexIndex, bikeRentalStation);
            }
        }
        if (numAddedStations > 0) {
            this.bikeSharing = true;
        }
        LOG.info("Added {} out of {} stations ratio:{}", numAddedStations, bikeRentalStations.size(), numAddedStations/bikeRentalStations.size());

    }
}
