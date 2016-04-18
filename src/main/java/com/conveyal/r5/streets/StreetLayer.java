package com.conveyal.r5.streets;

import com.conveyal.gtfs.model.Stop;
import com.conveyal.osmlib.*;
import com.conveyal.r5.api.util.BikeRentalStation;
import com.conveyal.r5.api.util.ParkRideParking;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.labeling.*;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.StreetMode;
import com.vividsolutions.jts.geom.*;
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
import java.util.stream.Collectors;
import java.util.stream.LongStream;

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
    public TIntObjectMap<ParkRideParking> parkRideLocationsMap;

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
    public EdgeStore edgeStore = new EdgeStore(vertexStore, this, 200_000);

    /** Turn restrictions can potentially have a large number of affected edges, so store them once and reference them */
    public List<TurnRestriction> turnRestrictions = new ArrayList<>();

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

        // keep track of ways that need to later become park and rides
        List<Way> parkAndRideWays = new ArrayList<>();

        for (Map.Entry<Long, Way> entry : osm.ways.entrySet()) {
            Way way = entry.getValue();

            if (way.hasTag("park_ride", "yes"))
                parkAndRideWays.add(way);

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

        List<Node> parkAndRideNodes = new ArrayList<>();

        for (Node node : osm.nodes.values()) {
            if (node.hasTag("park_ride", "yes")) parkAndRideNodes.add(node);
        }

        LOG.info("Done making street edges.");

        LOG.info("Made {} vertices and {} edges.", vertexStore.getVertexCount(), edgeStore.nEdges);
        LOG.info("Found {} P+R node candidates", parkAndRideNodes.size());


        // need edge lists to apply intersection costs
        buildEdgeLists();
        stressLabeler.applyIntersectionCosts(this);
//        TEMPORARILY REMOVING FOR SPEED IMPROVEMENT
//        if (removeIslands)
//            removeDisconnectedSubgraphs(MIN_SUBGRAPH_SIZE);

        // index the streets, we need the index to connect things to them.
        this.indexStreets();

        buildParkAndRideAreas(parkAndRideWays);
        buildParkAndRideNodes(parkAndRideNodes);

        VertexStore.Vertex vertex = vertexStore.getCursor();
        long numOfParkAndRides = 0;
        while (vertex.advance()) {
            if (vertex.getFlag(VertexStore.VertexFlag.PARK_AND_RIDE)) {
                numOfParkAndRides++;
            }
        }
        LOG.info("Made {} P+R vertices", numOfParkAndRides);

        // create turn restrictions.
        // TODO transit splitting is going to mess this up
        osm.relations.entrySet().stream().filter(e -> e.getValue().hasTag("type", "restriction")).forEach(e -> this.applyTurnRestriction(e.getKey(), e.getValue()));
        LOG.info("Created {} turn restrictions", turnRestrictions.size());

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
        String name = getName(edge.getOSMID(), locale);
        if (name == null) {
            //TODO: localize generated street names
            if (edge.getFlag(EdgeStore.EdgeFlag.STAIRS)) {
                return "stairs";
            } else if (edge.getFlag(EdgeStore.EdgeFlag.CROSSING)) {
                return "street crossing";
            } else if (edge.getFlag(EdgeStore.EdgeFlag.BIKE_PATH)) {
                return "bike path";
            } else if (edge.getFlag(EdgeStore.EdgeFlag.SIDEWALK)) {
                return "sidewalk";
            }
        }
        return name;
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
     * Gets all the OSM tags of specified OSM way
     *
     * Tags are returned as tag=value separated with ;
     *
     * AKA same format that {@link Way#setTagsFromString(String)} accepts
     *
     * @param edge for which to get tags
     * @return String with all the tags or null
     */
    public String getWayTags(EdgeStore.Edge edge) {
        if (osm == null) {
            return null;
        }
        Way way = osm.ways.get(edge.getOSMID());
        if (way != null && !way.hasNoTags()) {
            return way.tags.stream()
                .map(OSMEntity.Tag::toString)
                .collect(Collectors.joining(";"));
        }
        return null;

    }

    /** Connect areal park and rides to the graph */
    private void buildParkAndRideAreas(List<Way> parkAndRideWays) {
        VertexStore.Vertex v = this.vertexStore.getCursor();
        EdgeStore.Edge e = this.edgeStore.getCursor();
        parkRideLocationsMap = new TIntObjectHashMap<>();

        for (Way way : parkAndRideWays) {

            Coordinate[] coords = LongStream.of(way.nodes).mapToObj(nid -> {
                Node n = osm.nodes.get(nid);
                return new Coordinate(n.getLon(), n.getLat());
            }).toArray(s -> new Coordinate[s]);

            // nb using linestring not polygon so all found intersections are at edges.
            LineString g = GeometryUtils.geometryFactory.createLineString(coords);

            // create a vertex in the middle of the lot to reflect the park and ride
            Coordinate centroid = g.getCentroid().getCoordinate();
            int centerVertex = vertexStore.addVertex(centroid.y, centroid.x);
            v.seek(centerVertex);
            v.setFlag(VertexStore.VertexFlag.PARK_AND_RIDE);

            ParkRideParking parkRideParking = new ParkRideParking(centerVertex, way);
            parkRideLocationsMap.put(centerVertex, parkRideParking);

            // find nearby edges
            Envelope env = g.getEnvelopeInternal();
            TIntSet nearbyEdges = this.spatialIndex.query(VertexStore.envelopeToFixed(env));

            nearbyEdges.forEach(eidx -> {
                e.seek(eidx);
                LineString edgeGeometry = e.getGeometry();

                if (edgeGeometry.intersects(g)) {
                    // we found an intersection! yay!
                    Geometry intersection = edgeGeometry.intersection(g);

                    for (int i = 0; i < intersection.getNumGeometries(); i++) {
                        Geometry single = intersection.getGeometryN(i);

                        if (single instanceof Point) {
                            connectParkAndRide(centerVertex, single.getCoordinate(), e);
                        }

                        else if (single instanceof LineString) {
                            // coincident segments. TODO can this even happen?
                            // just connect start and end of coincident segment
                            Coordinate[] singleCoords = single.getCoordinates();

                            if (singleCoords.length > 0) {
                                connectParkAndRide(centerVertex, coords[0], e);

                                // TODO is conditional even necessary?
                                if (singleCoords.length > 1) {
                                    connectParkAndRide(centerVertex, coords[coords.length - 1], e);
                                }
                            }
                        }
                    }
                }

                return true;
            });

            // TODO check if we didn't connect anything and fall back to proximity based connection
        }
    }

    private void buildParkAndRideNodes (List<Node> nodes) {
        VertexStore.Vertex v = vertexStore.getCursor();
        for (Node node : nodes) {
            int vidx = vertexStore.addVertex(node.getLat(), node.getLon());
            v.seek(vidx);
            v.setFlag(VertexStore.VertexFlag.PARK_AND_RIDE);

            ParkRideParking parkRideParking = new ParkRideParking(vidx, node);
            parkRideLocationsMap.put(vidx, parkRideParking);

            int targetWalking = getOrCreateVertexNear(node.getLat(), node.getLon(), 500, true,
                StreetMode.WALK);
            EdgeStore.Edge created = edgeStore.addStreetPair(vidx, targetWalking, 1, -1);

            // allow link edges to be traversed by all, access is controlled by connected edges
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR);
            created.setFlag(EdgeStore.EdgeFlag.LINK);

            // and the back edge
            created.advance();
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR);
            created.setFlag(EdgeStore.EdgeFlag.LINK);

            int targetDriving = getOrCreateVertexNear(node.getLat(), node.getLon(), 500, true,
                StreetMode.CAR);
            //If both CAR and WALK links would connect to the same edge we can skip new useless edge
            if (targetDriving == targetWalking) {
                continue;
            }
            created = edgeStore.addStreetPair(vidx, targetDriving, 1, -1);

            // allow link edges to be traversed by all, access is controlled by connected edges
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR);
            created.setFlag(EdgeStore.EdgeFlag.LINK);

            // and the back edge
            created.advance();
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            created.setFlag(EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR);
            created.setFlag(EdgeStore.EdgeFlag.LINK);
        }
    }

    /** Connect a park and ride vertex to the street network at a particular location and edge */
    private void connectParkAndRide (int centerVertex, Coordinate coord, EdgeStore.Edge edge) {
        Split split = Split.findOnEdge(coord.y, coord.x, edge);
        int targetVertex = splitEdge(split);
        EdgeStore.Edge created = edgeStore.addStreetPair(centerVertex, targetVertex, 1, -1); // basically free to enter/leave P&R for now.
        // allow link edges to be traversed by all, access is controlled by connected edges
        created.setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
        created.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
        created.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        created.setFlag(EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR);
        created.setFlag(EdgeStore.EdgeFlag.LINK);

        // and the back edge
        created.advance();
        created.setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
        created.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
        created.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        created.setFlag(EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR);
        created.setFlag(EdgeStore.EdgeFlag.LINK);
    }

    private void applyTurnRestriction (long id, Relation restriction) {
        boolean only;

        if (!restriction.hasTag("restriction")) {
            LOG.error("Restriction {} has no restriction tag, skipping", id);
            return;
        }

        if (restriction.getTag("restriction").startsWith("no_")) only = false;
        else if (restriction.getTag("restriction").startsWith("only_")) only = true;
        else {
            LOG.error("Restriction {} has invalid restriction tag {}, skipping", id, restriction.getTag("restriction"));
            return;
        }

        TurnRestriction out = new TurnRestriction();
        out.only = only;

        // sort out the members
        Relation.Member from = null, to = null;
        List<Relation.Member> via = new ArrayList<>();

        for (Relation.Member member : restriction.members) {
            if ("from".equals(member.role)) {
                if (from != null) {
                    LOG.error("Turn restriction {} has multiple from members, skipping", id);
                    return;
                }

                from = member;
            }
            else if ("to".equals(member.role)) {
                if (to != null) {
                    LOG.error("Turn restriction {} has multiple to members, skipping", id);
                    return;
                }

                to = member;
            }
            else if ("via".equals(member.role)) {
                via.add(member);
            }
        }


        if (from == null || to == null || via.isEmpty()) {
            LOG.error("Invalid turn restriction {}, does not have from, to and via, skipping", id);
            return;
        }

        boolean hasWays = false, hasNodes = false;

        for (Relation.Member m : via) {
            if (m.type == OSMEntity.Type.WAY) hasWays = true;
            else if (m.type == OSMEntity.Type.NODE) hasNodes = true;
            else {
                LOG.error("via must be node or way, skipping restriction {}", id);
                return;
            }
        }

        if (hasWays && hasNodes || hasNodes && via.size() > 1) {
            LOG.error("via must be single node or one or more ways, skipping restriction {}", id);
            return;
        }

        EdgeStore.Edge e = edgeStore.getCursor();

        if (hasNodes) {
            // via node, this is a fairly simple turn restriction. First find the relevant vertex.
            int vertex = vertexIndexForOsmNode.get(via.get(0).id);

            if (vertex == -1) {
                LOG.warn("Vertex {} not found to use as via node for restriction {}, skipping this restriction", via.get(0).id, id);
                return;
            }

            // use array to dodge effectively final nonsense
            final int[] fromEdge = new int[] { -1 };
            final long fromWayId = from.id; // more effectively final nonsense
            final boolean[] bad = new boolean[] { false };

            // find the edges
            incomingEdges.get(vertex).forEach(eidx -> {
                e.seek(eidx);
                if (e.getOSMID() == fromWayId) {
                    if (fromEdge[0] != -1) {
                        LOG.error("From way enters vertex {} twice, restriction {} is therefore ambiguous, skipping", vertex, id);
                        bad[0] = true;
                        return false;
                    }

                    fromEdge[0] = eidx;
                }

                return true; // iteration should continue
            });


            final int[] toEdge = new int[] { -1 };
            final long toWayId = to.id; // more effectively final nonsense
            outgoingEdges.get(vertex).forEach(eidx -> {
                e.seek(eidx);
                if (e.getOSMID() == toWayId) {
                    if (toEdge[0] != -1) {
                        LOG.error("To way exits vertex {} twice, restriction {} is therefore ambiguous, skipping", vertex, id);
                        bad[0] = true;
                        return false;
                    }

                    toEdge[0] = eidx;
                }

                return true; // iteration should continue
            });

            if (bad[0]) return; // log message already printed

            if (fromEdge[0] == -1 || toEdge[0] == -1) {
                LOG.error("Did not find from/to edges for restriction {}, skipping", id);
                return;
            }

            // phew. create the restriction and apply it where needed
            out.fromEdge = fromEdge[0];
            out.toEdge = toEdge[0];

            int index = turnRestrictions.size();
            turnRestrictions.add(out);
            edgeStore.turnRestrictions.put(out.fromEdge, index);
        } else {
            // via member(s) are ways, which is more tricky
            // do a little street search constrained to the ways in question
            Way fromWay = osm.ways.get(from.id);
            long[][] viaNodes = via.stream().map(m -> osm.ways.get(m.id).nodes).toArray(i -> new long[i][]);
            Way toWay = osm.ways.get(to.id);

            // We do a little search, keeping in mind that there must be the same number of ways as there are via members
            List<long[]> nodes = new ArrayList<>();
            List<long[]> ways = new ArrayList<>();

            // loop over from way to initialize search
            for (long node : fromWay.nodes) {
                for (int viaPos = 0; viaPos < viaNodes.length; viaPos++) {
                    for (long viaNode : viaNodes[viaPos]) {
                        if (node == viaNode) {
                            nodes.add(new long[] { node });
                            ways.add(new long[] { via.get(viaPos).id });
                        }
                    }
                }
            }

            List<long[]> previousNodes;
            List<long[]> previousWays;

            // via.size() - 1 because we've already explored one via way where we transferred from the the from way
            for (int round = 0; round < via.size() - 1; round++) {
                previousNodes = nodes;
                previousWays = ways;

                nodes = new ArrayList<>();
                ways = new ArrayList<>();

                for (int statePos = 0; statePos < previousNodes.size(); statePos++) {
                    // get the way we are on and search all its nodes
                    long wayId = previousWays.get(statePos)[round];
                    Way way = osm.ways.get(wayId);

                    for (long node : way.nodes) {
                        VIA:
                        for (int viaPos = 0; viaPos < viaNodes.length; viaPos++) {
                            long viaWayId = via.get(viaPos).id;

                            // don't do looping searches
                            for (long prevWay : previousWays.get(statePos)) {
                                if (viaWayId == prevWay) continue VIA;
                            }

                            for (long viaNode : osm.ways.get(viaWayId).nodes) {
                                if (viaNode == node) {
                                    long[] newNodes = Arrays.copyOf(previousNodes.get(statePos), round + 2);
                                    long[] newWays = Arrays.copyOf(previousWays.get(statePos), round + 2);

                                    newNodes[round + 1] = node;
                                    newWays[round + 1] = viaWayId;

                                    nodes.add(newNodes);
                                    ways.add(newWays);
                                }
                            }
                        }
                    }
                }
            }

            // now filter them to just ones that reach the to way
            long[] pathNodes = null;
            long[] pathWays = null;

            for (int statePos = 0; statePos < nodes.size(); statePos++) {
                long[] theseWays = ways.get(statePos);
                Way finalWay = osm.ways.get(theseWays[theseWays.length - 1]);

                for (long node : finalWay.nodes) {
                    for (long toNode : toWay.nodes) {
                        if (node == toNode) {
                            if (pathNodes != null) {
                                LOG.error("Turn restriction {} has ambiguous via ways (multiple paths through via ways between from and to), skipping", id);
                                return;
                            }

                            pathNodes = Arrays.copyOf(nodes.get(statePos), theseWays.length + 1);
                            pathNodes[pathNodes.length - 1] = node;
                            pathWays = theseWays;
                        }
                    }
                }
            }

            if (pathNodes == null) {
                LOG.error("Invalid turn restriction {}, no way from from to to via via, skipping", id);
                return;
            }

            // convert OSM nodes and ways into IDs
            // first find the fromEdge and toEdge. dodge effectively final nonsense
            final int[] fromEdge = new int[] { -1 };
            final long fromWayId = from.id; // more effectively final nonsense
            final boolean[] bad = new boolean[] { false };

            int fromVertex = vertexIndexForOsmNode.get(pathNodes[0]);

            // find the edges
            incomingEdges.get(fromVertex).forEach(eidx -> {
                e.seek(eidx);
                if (e.getOSMID() == fromWayId) {
                    if (fromEdge[0] != -1) {
                        LOG.error("From way enters vertex {} twice, restriction {} is therefore ambiguous, skipping", fromVertex, id);
                        bad[0] = true;
                        return false;
                    }

                    fromEdge[0] = eidx;
                }

                return true; // iteration should continue
            });

            int toVertex = vertexIndexForOsmNode.get(pathNodes[pathNodes.length - 1]);

            final int[] toEdge = new int[] { -1 };
            final long toWayId = to.id; // more effectively final nonsense
            outgoingEdges.get(toVertex).forEach(eidx -> {
                e.seek(eidx);
                if (e.getOSMID() == toWayId) {
                    if (toEdge[0] != -1) {
                        LOG.error("To way exits vertex {} twice, restriction {} is therefore ambiguous, skipping", toVertex, id);
                        bad[0] = true;
                        return false;
                    }

                    toEdge[0] = eidx;
                }

                return true; // iteration should continue
            });

            if (bad[0]) return; // log message already printed

            if (fromEdge[0] == -1 || toEdge[0] == -1) {
                LOG.error("Did not find from/to edges for restriction {}, skipping", id);
                return;
            }

            out.fromEdge = fromEdge[0];
            out.toEdge = toEdge[0];

            // edges affected by this turn restriction. Make a list in case something goes awry when trying to find edges
            TIntList affectedEdges = new TIntArrayList();

            // now apply to all via ways.
            // > 0 is intentional. pathNodes[0] is the node on the from edge
            for (int nidx = pathNodes.length - 1; nidx > 0; nidx--) {
                final int[] edge = new int[] { -1 };
                // fencepost problem: one more node than ways
                final long wayId = pathWays[nidx - 1]; // more effectively final nonsense
                int vertex = vertexIndexForOsmNode.get(pathNodes[nidx]);
                incomingEdges.get(vertex).forEach(eidx -> {
                    e.seek(eidx);
                    if (e.getOSMID() == wayId) {
                        if (edge[0] != -1) {
                            // TODO we've already started messing with data structures!
                            LOG.error("To way exits vertex {} twice, restriction {} is therefore ambiguous, skipping", vertex, id);
                            bad[0] = true;
                            return false;
                        }

                        edge[0] = eidx;
                    }

                    return true; // iteration should continue
                });

                if (bad[0]) return; // log message already printed
                if (edge[0] == -1) {
                    LOG.warn("Did not find via way {} for restriction {}, skipping", wayId, id);
                    return;
                }

                affectedEdges.add(edge[0]);
            }

            affectedEdges.reverse();

            out.viaEdges = affectedEdges.toArray();

            int index = turnRestrictions.size();
            turnRestrictions.add(out);
            edgeStore.turnRestrictions.put(out.fromEdge, index);

            // take a deep breath
        }
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
            spatialIndex.insert(edge.getGeometry(), e);
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
     * This uses {@link #findSplit(double, double, double, StreetMode)} and {@link Split} which need filled spatialIndex
     * In other works {@link #indexStreets()} needs to be called before this is used. Otherwise no near vertex is found.
     *
     * TODO maybe use X and Y everywhere for fixed point, and lat/lon for double precision degrees.
     * TODO maybe move this into Split.perform(), store streetLayer ref in Split.
     * @param lat latitude in floating point geographic (not fixed point) degrees.
     * @param lon longitude in floating point geographic (not fixed point) degrees.
     * @param radiusMeters the radius of a circle in meters within which to search for nearby streets.
     * @param destructive if this is true, allow the street splitting process to change existing edge geometries.
     *                    Set to false when performing a temporary modification that must be reversible.
     * @param streetMode Link to edges which have permission for StreetMode
     * @return the index of a street vertex very close to the supplied location,
     *         or -1 if no such vertex could be found or created.
     */
    public int getOrCreateVertexNear(double lat, double lon, double radiusMeters,
        boolean destructive, StreetMode streetMode) {
        Split split = findSplit(lat, lon, radiusMeters, streetMode);
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
        int oldToVertex = edge.getToVertex(); // Grab this value before potentially modifying the existing edge.
        if (destructive) {
            // Modify the existing bidirectional edge pair to serve as the first segment leading up to the split point.
            // Its spatial index entry is still valid, since the edge's envelope will only shrink.
            edge.setLengthMm(split.distance0_mm);
            edge.setToVertex(newVertexIndex);
            // Turn the edge into a straight line. FIXME split edges and new edges should have geometries!
            edge.setGeometry(Collections.EMPTY_LIST);
        } else {
            // Preserve the existing edge pair, creating a new edge pair to lead up to the split.
            // The new edge will be added to the edge lists later (the edge lists are a transient index).
            EdgeStore.Edge newEdge0 = edgeStore.addStreetPair(edge.getFromVertex(), newVertexIndex, split.distance0_mm, edge.getOSMID());
            spatialIndex.insert(newEdge0.getEnvelope(), newEdge0.edgeIndex);
            // Copy the flags and speeds for both directions, making the new edge like the existing one.
            newEdge0.copyPairFlagsAndSpeeds(edge);
        }
        // Make a second new bidirectional edge pair after the split and add it to the spatial index.
        // The new edge will be added to the edge lists later (the edge lists are a transient index).
        EdgeStore.Edge newEdge1 = edgeStore.addStreetPair(newVertexIndex, oldToVertex, split.distance1_mm, edge.getOSMID());
        spatialIndex.insert(newEdge1.getEnvelope(), newEdge1.edgeIndex);
        // Copy the flags and speeds for both directions, making the new edge like the existing one.
        newEdge1.copyPairFlagsAndSpeeds(edge);

        // don't allow the router to make ill-advised U-turns at splitter vertices


        // Return the splitter vertex ID
        return newVertexIndex;
    }

    /** perform destructive splitting of edges
     * FIXME: currently used only in P+R it should probably be changed to use getOrCreateVertexNear */
    public int splitEdge(Split split) {
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

        // clean up any turn restrictions that exist
        // turn restrictions on the forward edge go to the new edge's forward edge. Turn restrictions on the back edge stay
        // where they are
        edgeStore.turnRestrictions.removeAll(split.edge).forEach(ridx -> edgeStore.turnRestrictions.put(newEdge.edgeIndex, ridx));

        return newVertexIndex;
        // TODO store street-to-stop distance in a table in TransitLayer. This also allows adjusting for subway entrances etc.
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
     * @param destructive if this is true, allow changes to existing edges, vertices, and geometries.
     *                    Set to false when performing a temporary modification that must be reversible.
     * @return the vertex of the newly created vertex at the supplied coordinates.
     */
    public int createAndLinkVertex (double lat, double lon, double radiusMeters, boolean destructive) {
        int stopVertex = vertexStore.addVertex(lat, lon);
        int streetVertex = getOrCreateVertexNear(lat, lon, radiusMeters, destructive,
            StreetMode.WALK);

        // unlinked
        if (streetVertex == -1) return -1;

        EdgeStore.Edge e = edgeStore.addStreetPair(stopVertex, streetVertex, 1, -1); // TODO maybe link edges should have a length.

        // all permissions true, permissions are controlled by whatever leads into this edge.
        e.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        e.setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
        e.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
        e.setFlag(EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR);
        e.advance();
        // set flags on reverse edge
        e.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        e.setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
        e.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
        e.setFlag(EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR);

        return stopVertex;
    }

    /**
     * Find a split. Deprecated in favor of finding a split for a particular mode, below.
     */
    @Deprecated
    public Split findSplit (double lat, double lon, double radiusMeters) {
        return findSplit(lat, lon, radiusMeters, null);
    }

    /**
     * Non-destructively find a location on an existing street near the given point.
     * TODO favor platforms and pedestrian paths when requested
     * @param lat latitude in floating point geographic coordinates (not fixed point int coordinates)
     * @param lon longitude in floating point geographic coordinates (not fixed point int coordinates)
     * @return a Split object representing a point along a sub-segment of a specific edge, or null if there are no streets nearby.
     */
    public Split findSplit(double lat, double lon, double radiusMeters, StreetMode streetMode) {
        return Split.find (lat, lon, radiusMeters, this, streetMode);
    }

    /**
     * For every stop in a TransitLayer, find or create a nearby vertex in the street layer and record the connection
     * between the two. This is a destructive process in that it modifies existing edges, and should therefore only
     * be done during initial transport network building. Stops may be added later but must use destructive = false.
     */
    public void associateStops (TransitLayer transitLayer, int radiusMeters) {
        for (Stop stop : transitLayer.stopForIndex) {
            int stopVertex = createAndLinkVertex(stop.stop_lat, stop.stop_lon, radiusMeters, true);
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
            r.streetMode = StreetMode.WALK;
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

            // when origin is a vertex, the origin vertex is not included in the result
            reachedVertices.add(vertex);
            vertexLabels.put(vertex, vertex);

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
            int streetVertexIndex = getOrCreateVertexNear(bikeRentalStation.lat, bikeRentalStation.lon, radiusMeters, true,
                StreetMode.WALK);
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

    private static class TurnRestrictionSearchState {
        public TurnRestrictionSearchState back;
        public long node;
        public long backWay;
    }
}
