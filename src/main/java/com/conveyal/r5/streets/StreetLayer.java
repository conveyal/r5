package com.conveyal.r5.streets;

import com.conveyal.gtfs.model.Stop;
import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.Way;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.labeling.*;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.streets.EdgeStore.Edge;
import com.vividsolutions.jts.geom.Envelope;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TLongIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import com.conveyal.r5.transit.TransitLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * The radius of a circle in meters within which to search for nearby streets.
     * This should not necessarily be a constant, but even if it's made settable it should be a field to avoid
     * cluttering method signatures. Generally you'd set this once at startup and always use the same value afterward.
     */
    public static final double LINK_RADIUS_METERS = 300;

    // Edge lists should be constructed after the fact from edges. This minimizes serialized size too.
    public transient List<TIntList> outgoingEdges;
    public transient List<TIntList> incomingEdges;
    private transient IntHashGrid spatialIndex = new IntHashGrid();

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
                    makeEdge(way, beginIdx, n);
                    nEdgesCreated += 1;
                    beginIdx = n;
                }
            }
            edgesPerWayHistogram.add(nEdgesCreated);
        }
        LOG.info("Done making street edges.");
        LOG.info("Made {} vertices and {} edges.", vertexStore.getVertexCount(), edgeStore.nEdges());

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
     * @return the length of the edge in millimeters, or -1 if that length will overflow a 32 bit int
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
     * Make an edge for a sub-section of an OSM way, typically between two intersections or leading up to a dead end.
     */
    private void makeEdge (Way way, int beginIdx, int endIdx) {

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

        EdgeStore.Edge newEdge = edgeStore.addStreetPair(beginVertexIndex, endVertexIndex, edgeLengthMillimeters);
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
        for (int e = 0; e < edgeStore.nEdges(); e += 2) {
            edge.seek(e);
            spatialIndex.insert(edge.getGeometry(), e);
        }
        LOG.info("Done indexing streets.");
    }

    /**
     * Rather than querying the spatial index directly, going through this method will ensure that any temporary edges
     * not in the index are also visible. Temporary edges, created when applying a scenario in a single thread, are
     * not added to the spatial index, which is read by all threads.
     * Note: the spatial index can and will return false positives, but should not produce false negatives.
     * We return the unfiltered results including false positives because calculating the true distance to each edge
     * is quite a slow operation. The caller must post-filter the set of edges if more distance information is needed,
     * including knowledge of whether an edge passes inside the query envelope at all.
     */
    public TIntSet findEdgesInEnvelope (Envelope envelope) {
        TIntSet candidates = spatialIndex.query(envelope);
        // Always include any temporary edges, since over-selection is allowed.
        candidates.addAll(edgeStore.temporarilyAddedEdges);
        return candidates;
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

    /**
     * The edge lists (which edges go out of and come into each vertex) are derived from the edges in the EdgeStore.
     * So any time you add edges or change their endpoints, you need to rebuild the edge index.
     * TODO some way to signal that a few new edges have been added, rather than rebuilding the whole lists.
     */
    public void buildEdgeLists() {
        LOG.info("Building edge lists from edges...");
        outgoingEdges = new ArrayList<>(vertexStore.getVertexCount());
        incomingEdges = new ArrayList<>(vertexStore.getVertexCount());
        for (int v = 0; v < vertexStore.getVertexCount(); v++) {
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
     * Find an existing street vertex near the supplied coordinates, or create a new one if there are no vertices
     * near enough.
     * TODO maybe use X and Y everywhere for fixed point, and lat/lon for double precision degrees.
     * TODO maybe move this into Split.perform(), store streetLayer ref in Split.
     * @param lat latitude in floating point geographic (not fixed point) degrees.
     * @param lon longitude in floating point geographic (not fixed point) degrees.
     * @return the index of a street vertex very close to the supplied location,
     *         or -1 if no such vertex could be found or created.
     */
    public int getOrCreateVertexNear (double lat, double lon) {
        Split split = findSplit(lat, lon, LINK_RADIUS_METERS);
        if (split == null) {
            // No linking site was found within range.
            return -1;
        }
        // We have a linking site on a street edge. Find or make a suitable vertex at that site.
        // It is not necessary to retain the original Edge cursor object inside findSplit, one object instantiation is harmless.
        EdgeStore.Edge edge = edgeStore.getCursor(split.edge);

        // Check for cases where we don't need to create a new vertex:
        // The linking site is very near an intersection, or the edge is reached end-wise.
        if (split.distance0_mm < SNAP_RADIUS_MM || split.distance1_mm < SNAP_RADIUS_MM) {
            if (split.distance0_mm < split.distance1_mm) {
                // Very close to the beginning of the edge. Return that existing vertex.
                return edge.getFromVertex();
            } else {
                // Very close to the end of the edge. Return that existing vertex.
                return edge.getToVertex();
            }
        }

        // The split is somewhere along a street away from an existing intersection vertex. Make a new splitter vertex.
        int newVertexIndex = vertexStore.addVertexFixed((int) split.fLat, (int) split.fLon);
        int oldToVertex = edge.getToVertex(); // Hold a copy of the to vertex index, because it may be modified below.
        if (edge.isMutable()) {
            // The edge we are going to split is mutable.
            // We're either building a baseline graph, or modifying an edge created within the same scenario.
            // Modify the existing bidirectional edge pair to serve as the first segment leading up to the split point.
            // Its spatial index entry is still valid, since the edge's envelope will only shrink.
            edge.setLengthMm(split.distance0_mm);
            edge.setToVertex(newVertexIndex);
            // Turn the edge into a straight line. FIXME split edges and new edges should have geometries!
            edge.setGeometry(Collections.EMPTY_LIST);
        } else {
            // The edge we are going to split is immutable, and should be left as-is.
            // We must be applying a scenario, and this edge is part of the baseline graph shared between threads.
            // Preserve the existing edge pair, creating a new edge pair to lead up to the split.
            // The new edge will be added to the edge lists later (the edge lists are a transient index).
            // We don't add it to the spatial index, which is shared between all threads. TODO include all temp edges in every spatial index query result.
            EdgeStore.Edge newEdge0 = edgeStore.addStreetPair(edge.getFromVertex(), newVertexIndex, split.distance0_mm);
            // Copy the flags and speeds for both directions, making the new edge like the existing one.
            newEdge0.copyPairFlagsAndSpeeds(edge);
        }
        // Make a new bidirectional edge pair for the segment after the split.
        // The new edge will be added to the edge lists later (the edge lists are a transient index).
        EdgeStore.Edge newEdge1 = edgeStore.addStreetPair(newVertexIndex, oldToVertex, split.distance1_mm);
        // Copy the flags and speeds for both directions, making the new edge1 like the existing edge.
        newEdge1.copyPairFlagsAndSpeeds(edge);
        if (!edgeStore.isProtectiveCopy()) {
            spatialIndex.insert(newEdge1.getEnvelope(), newEdge1.edgeIndex);
        }

        // Return the splitter vertex ID
        return newVertexIndex;
    }

    /**
     * Create a street-layer vertex representing a transit stop, and connect that new vertex to the street network if
     * possible. The vertex will be created and assigned an index whether or not it is successfully linked to the streets.
     * This is intended for transit stop linking. It always creates a new vertex in the street layer exactly at the
     * coordinates provided. You can be sure to receive a unique vertex index each time it's called on the same street layer.
     * Once it has created this new vertex, it will look for the nearest edge in the street network and link the newly
     * created vertex to the closest point on that nearby edge.
     * The process of linking to that edge may or may not create a second new splitter vertex along that edge.
     * If the newly created vertex is near an intersection or another splitter vertex, the existing vertex will be
     * reused. So in sum, this will create one or two new vertices, and all necessary edge pairs to properly connect
     * these new vertices.
     * TODO store street-to-stop distance in a table in TransitLayer, or change the link edge length. This also allows adjusting for subway entrances etc.
     * @return the vertex of the newly created vertex at the supplied coordinates.
     */
    public int createAndLinkVertex (double lat, double lon) {
        int stopVertex = vertexStore.addVertex(lat, lon);
        int streetVertex = getOrCreateVertexNear(lat, lon);
        Edge e = edgeStore.addStreetPair(stopVertex, streetVertex, 1); // TODO maybe link edges should have a length.
        // Allow all modes to traverse street-to-transit link edges.
        // In practice, mode permissions will be controlled by whatever street edges lead up to these link edges.
        e.allowAllModes(); // forward edge
        e.advance();
        e.allowAllModes(); // backward edge
        return stopVertex;
    }

    /**
     * Find a location on an existing street near the given point, without actually creating any vertices or edges.
     * The search radius can be specified freely here because we use this function to link transit stops to streets but
     * also to link pointsets to streets, and currently we use different distances for these two things.
     * TODO favor platforms and pedestrian paths when requested
     * @param lat latitude in floating point geographic coordinates (not fixed point int coordinates)
     * @param lon longitude in floating point geographic coordinates (not fixed point int coordinates)
     * @return a Split object representing a point along a sub-segment of a specific edge, or null if there are no streets nearby.
     */
    public Split findSplit(double lat, double lon, double searchRadiusMeters) {
        return Split.find(lat, lon, searchRadiusMeters, this);
    }

    /**
     * For every stop in a TransitLayer, find or create a nearby vertex in the street layer and record the connection
     * between the two.
     */
    public void associateStops (TransitLayer transitLayer) {
        for (Stop stop : transitLayer.stopForIndex) {
            int stopVertex = createAndLinkVertex(stop.stop_lat, stop.stop_lon);
            transitLayer.streetVertexForStop.add(stopVertex); // This is always a valid, unique vertex index.
            // The inverse stopForStreetVertex map is a transient, derived index and will be built later.
        }
        // Establish bidirectional reference between the StreetLayer and the TransitLayer.
        transitLayer.linkedStreetLayer = this;
        this.linkedTransitLayer = transitLayer;
    }

    public int getVertexCount() {
        return vertexStore.getVertexCount();
    }

    /**
     * Find and remove all subgraphs with fewer than minSubgraphSize vertices. Uses a flood fill
     * algorithm, see http://stackoverflow.com/questions/1348783.
     */
    public void removeDisconnectedSubgraphs(int minSubgraphSize) {
        LOG.info("Removing subgraphs with fewer than {} vertices", minSubgraphSize);
        boolean edgeListsBuilt = incomingEdges != null;

        int nSmallSubgraphs = 0;

        if (!edgeListsBuilt)
            buildEdgeLists();

        // labels for the flood fill algorithm
        TIntIntMap vertexLabels = new TIntIntHashMap();

        // vertices and edges that should be removed
        TIntSet verticesToRemove = new TIntHashSet();
        TIntSet edgesToRemove = new TIntHashSet();

        int nOrigins = 0;
        for (int vertex = 0; vertex < vertexStore.getVertexCount(); vertex++) {
            // N.B. this is not actually running a search for every vertex as after the first few
            // almost all of the vertices are labeled
            if (vertexLabels.containsKey(vertex))
                continue;
            StreetRouter r = new StreetRouter(this);
            r.setOrigin(vertex);
            // walk to the end of the graph
            r.distanceLimitMeters = Integer.MAX_VALUE;
            r.route();
            nOrigins++;
            if (nOrigins % 100 == 0) {
                LOG.info("Searched from vertex number {}, {} total searches performed, {} islands slated for removal.", vertex, nOrigins, nSmallSubgraphs);
            }

            TIntList reachedVertices = new TIntArrayList();
            int nReached = 0;
            for (int reachedVertex = 0; reachedVertex < vertexStore.getVertexCount(); reachedVertex++) {
                if (r.getTravelTimeToVertex(reachedVertex) != Integer.MAX_VALUE) {
                    nReached++;
                    // use source vertex as label, saves a variable
                    vertexLabels.put(reachedVertex, vertex);
                    reachedVertices.add(reachedVertex);
                }
            }

            if (nReached < minSubgraphSize) {
                nSmallSubgraphs++;
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
        // TODO remove the vertices as well? this would be messy because the edges reference the vertices by index, and those indexes would change upon vertex removal.

        // don't forget this
        if (edgeListsBuilt) {
            buildEdgeLists();
            indexStreets();
        }
        else {
            incomingEdges = null;
            outgoingEdges = null;
        }

        if (nSmallSubgraphs > 0)
            LOG.info("Removed {} disconnected subgraphs", nSmallSubgraphs);
        else
            LOG.info("Found no subgraphs to remove, congratulations for having clean OSM data.");
        LOG.info("Done removing subgraphs. {} edges remain", edgeStore.nEdges());
    }

    public Envelope getEnvelope() {
        return envelope;
    }

    public List<TIntList> getOutgoingEdges() {
        return outgoingEdges;
    }

    public StreetLayer extendOnlyCopy () {
        return null;
    }

    public boolean isExtendOnlyCopy() {
        return this.edgeStore.isProtectiveCopy();
    }

}
