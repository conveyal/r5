package com.conveyal.r5.streets;

import com.conveyal.gtfs.model.Stop;
import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.OSMEntity;
import com.conveyal.osmlib.Relation;
import com.conveyal.osmlib.Way;
import com.conveyal.r5.analyst.scenario.PickupWaitTimes;
import com.conveyal.r5.api.util.BikeRentalStation;
import com.conveyal.r5.api.util.ParkRideParking;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.labeling.LevelOfTrafficStressLabeler;
import com.conveyal.r5.labeling.RoadPermission;
import com.conveyal.r5.labeling.SpeedLabeler;
import com.conveyal.r5.labeling.TraversalPermissionLabeler;
import com.conveyal.r5.labeling.TypeOfEdgeLabeler;
import com.conveyal.r5.labeling.USTraversalPermissionLabeler;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore.Edge;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.P2;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TLongIntMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.set.TIntSet;
import org.geotools.geojson.geom.GeometryJSON;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.conveyal.r5.analyst.scenario.PickupWaitTimes.NO_WAIT_ALL_STOPS;
import static com.conveyal.r5.streets.VertexStore.fixedDegreeGeometryToFloating;

/**
 * This class stores the street network. Information about public transit is in a separate layer.
 *
 * Is is currently implemented as a column store.
 *
 * We could also use something like https://github.com/RichardWarburton/slab which simulates Java objects in a chunk
 * of memory that could be mapped to a file. This could be useful for routing across continents or large countries.
 *
 * While the transit search is very fast (probably because it tends to search in order over contiguous arrays) the
 * street searches can be surprisingly slow. We suspect this is due to the vertices being in somewhat random order
 * in memory. The solution would be to order the vertices in memory according to their proximity in the graph,
 * then sort edges according to from-vertex order.
 *
 * Really what you want to do is embed the distance metric defined by the graph in 1D space. This is a 'metric embedding'
 * or multidimensional scaling, analagous to force-directed graph layout. http://ceur-ws.org/Vol-733/paper_pacher.pdf
 * You could do something similar using their geographic coordinates (Morton-code-sort the vertices).
 */
public class StreetLayer implements Serializable, Cloneable {

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

    /**
     * The radius below which we will not split a street, and will instead connect to an existing intersection.
     * i.e. if the requested split point is less than this distance from an existing vertex (edge endpoint) we'll just
     * return that existing endpoint.
     */
    private static final int SNAP_RADIUS_MM = 5 * 1000;

    /**
     * The radius of a circle in meters within which to search for nearby streets.
     * This should not necessarily be a constant, but even if it's made settable it should be stored in a field on this
     * class to avoid cluttering method signatures. Generally you'd set this once at startup and always use the same
     * value afterward.
     * 1.6km is really far to walk off a street. But some places have offices in the middle of big parking lots.
     */
    public static final double LINK_RADIUS_METERS = 1600;

    /**
     * Searching for streets takes a fair amount of computation, and the number of streets examined grows roughly as
     * the square of the radius. In most cases, the closest street is close to the search center point. If the specified
     * search radius exceeds this value, a mini-search will first be conducted to check for close-by streets before
     * examining every street within the full specified search radius.
     */
    public static final int INITIAL_LINK_RADIUS_METERS = 300;

    // Edge lists should be constructed after the fact from edges. This minimizes serialized size too.
    public transient List<TIntList> outgoingEdges;
    public transient List<TIntList> incomingEdges;

    /** A spatial index of all street network edges, using fixed-point WGS84 coordinates. */
    public transient IntHashGrid spatialIndex = new IntHashGrid();

    /**
     * Spatial index of temporary edges from a scenario. We used to not have this, and we used to return all
     * temporarily added edges in every spatial index query (because spatial indexes are allowed to over-select, and
     * are filtered). However, we now create scenarios with thousands of temporary edges (from thousands of added
     * transit stops), so we keep two spatial indexes, one for the baseline network and one for the scenario additions.
     */
    private transient IntHashGrid temporaryEdgeIndex;

    // Key is street vertex index, value is BikeRentalStation (with name, number of bikes, spaces id etc.)
    public TIntObjectMap<BikeRentalStation> bikeRentalStationMap;
    public TIntObjectMap<ParkRideParking> parkRideLocationsMap;

    // TODO these are only needed when building the network, should we really be keeping them here in the layer?
    //      We should instead have a network builder that holds references to this transient state.
    // TODO don't hardwire to US
    private transient TraversalPermissionLabeler permissionLabeler = new USTraversalPermissionLabeler();
    private transient LevelOfTrafficStressLabeler stressLabeler = new LevelOfTrafficStressLabeler();
    private transient TypeOfEdgeLabeler typeOfEdgeLabeler = new TypeOfEdgeLabeler();
    private transient SpeedLabeler speedLabeler;
    // private transient TraversalTimeLabeler traversalTimeLabeler;
    // This is only used when loading from OSM, and is then nulled to save memory.
    transient OSM osm;

    /** Envelope of this street layer, in decimal degrees (floating, not fixed-point) */
    public Envelope envelope = new Envelope();

    TLongIntMap vertexIndexForOsmNode = new TLongIntHashMap(100_000, 0.75f, -1, -1);

    // Initialize these when we have an estimate of the number of expected edges.
    public VertexStore vertexStore = new VertexStore(100_000);
    public EdgeStore edgeStore = new EdgeStore(vertexStore, this, 200_000);

    /**
     * Turn restrictions can potentially affect (include) several edges, so they are stored here and referenced
     * by index within all edges that are affected by them. TODO what if an edge is affected by multiple restrictions?
     */
    public List<TurnRestriction> turnRestrictions = new ArrayList<>();

    /**
     * The TransportNetwork containing this StreetLayer. This link up the object tree also allows us to access the
     * TransitLayer associated with this StreetLayer of the same TransportNetwork without maintaining bidirectional
     * references between the two layers.
     */
    public TransportNetwork parentNetwork = null;


    /**
     * A string uniquely identifying the contents of this StreetLayer among all StreetLayers.
     * When no scenario has been applied, this field will contain the ID of the enclosing TransportNetwork.
     * When a scenario has modified this StreetLayer, this field will be changed to that scenario's ID.
     * We need a way to know what information is in the network independent of object identity, which is lost in a
     * round trip through serialization. This also allows re-using cached linkages for several scenarios as long as
     * they don't modify the street network.
     */
    public String scenarioId;

    /**
     * Some StreetLayers are created by applying a scenario to an existing StreetLayer. All the contents of the base
     * StreetLayer are not copied, they are wrapped to make them extensible. These are called "scenario copies".
     * If this StreetLayer is such a scenario copy, this field points to the original StreetLayer it was based upon.
     * Otherwise this field should be null.
     */
    public StreetLayer baseStreetLayer = null;

    /**
     * This set of polygons specifies a spatially varying wait time to use a ride hailing service. Negative wait times
     * mean the service is not available at a particular location. If this reference is null, no wait time is applied.
     * Note that this is a single field, rather than a collection: we only support one set of polygons for one mode.
     */
    public PickupWaitTimes pickupWaitTimes;

    public static final EnumSet<EdgeStore.EdgeFlag> ALL_PERMISSIONS = EnumSet
        .of(EdgeStore.EdgeFlag.ALLOWS_BIKE, EdgeStore.EdgeFlag.ALLOWS_CAR,
            EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.NO_THRU_TRAFFIC,
            EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_BIKE, EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_PEDESTRIAN,
            EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR);

    public boolean bikeSharing = false;

    public StreetLayer(TNBuilderConfig tnBuilderConfig) {
        speedLabeler = new SpeedLabeler(tnBuilderConfig.speeds);
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
        if (!osm.intersectionDetection) {
            throw new IllegalArgumentException("Intersection detection not enabled on OSM source");
        }
        LOG.info("Making street edges from OSM ways...");
        this.osm = osm;

        // keep track of ways that need to later become park and rides
        List<Way> parkAndRideWays = new ArrayList<>();

        // TEMPORARY HACK: create a GeneralizedCosts object to hold costs from preprocessed OSM data, and indicate that
        // we are loading them. Eventually this should be done based on configuration settings.
        this.edgeStore.edgeTraversalTimes = new EdgeTraversalTimes(edgeStore);

        for (Map.Entry<Long, Way> entry : osm.ways.entrySet()) {
            Way way = entry.getValue();
            if (isParkAndRide(way)) {
                parkAndRideWays.add(way);
            }
            if (!isWayRoutable(way)) {
                continue;
            }
            int nEdgesCreated = 0;
            int beginIdx = 0;
            // Break each OSM way into topological segments between intersections, and make one edge pair per segment.
            for (int n = 1; n < way.nodes.length; n++) {
                if (osm.intersectionNodes.contains(way.nodes[n]) || n == (way.nodes.length - 1)) {
                    makeEdgePair(way, beginIdx, n, entry.getKey());
                    nEdgesCreated += 1;
                    beginIdx = n;
                }
            }
        }
        stressLabeler.logErrors();

        if (edgeStore.edgeTraversalTimes != null) {
            LOG.info("Summarizing per-edge traversal and turn times:");
            edgeStore.edgeTraversalTimes.summarize();
        } else {
            LOG.info("This street layer does not contain per-edge traversal and turn times.");
        }

        // Summarize LTS statistics
        // FIXME why is this summary printed before stressLabeler.applyIntersectionCosts below?
        Edge cursor = edgeStore.getCursor();
        cursor.seek(0);

        int lts1 = 0, lts2 = 0, lts3 = 0, lts4 = 0, ltsUnknown = 0;

        do {
            if (cursor.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_1)) lts1++;
            else if (cursor.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_2)) lts2++;
            else if (cursor.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_3)) lts3++;
            else if (cursor.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_4)) lts4++;
            else ltsUnknown++;
        } while (cursor.advance());

        LOG.info("Surrogate LTS:\n  1: {} edges\n  2: {} edges\n  3: {} edges\n  4: {} edges\n  Unknown: {} edges",
                lts1, lts2, lts3, lts4, ltsUnknown);

        List<Node> parkAndRideNodes = new ArrayList<>();
        for (Node node : osm.nodes.values()) {
            if (isParkAndRide(node)) {
                parkAndRideNodes.add(node);
            }
        }

        LOG.info("Done making street edges.");
        LOG.info("Made {} vertices and {} edges.", vertexStore.getVertexCount(), edgeStore.nEdges());
        LOG.info("Found {} P+R node candidates", parkAndRideNodes.size());

        // We need edge lists to apply intersection costs.
        buildEdgeLists();
        stressLabeler.applyIntersectionCosts(this);
        if (removeIslands) {
            new TarjanIslandPruner(this, MIN_SUBGRAPH_SIZE, StreetMode.CAR).run();
            // due to bike walking, walk must go before bike, see comment in TarjanIslandPruner javadoc
            new TarjanIslandPruner(this, MIN_SUBGRAPH_SIZE, StreetMode.WALK).run();
            new TarjanIslandPruner(this, MIN_SUBGRAPH_SIZE, StreetMode.BICYCLE).run();
        }

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

        // Create turn restrictions from relations.
        // TODO transit splitting is going to mess this up
        // TODO handle multipolygon relations that are park and rides (e.g. parking structures with holes)
        osm.relations.entrySet().stream().filter(e -> e.getValue().hasTag("type", "restriction")).forEach(e -> this.applyTurnRestriction(e.getKey(), e.getValue()));
        LOG.info("Created {} turn restrictions", turnRestrictions.size());

        //edgesPerWayHistogram.display();
        //pointsPerEdgeHistogram.display();
        // Clear unneeded indexes, allow them to be gc'ed
        if (!saveVertexIndex)
            vertexIndexForOsmNode = null;

        osm = null;
    }

    private boolean isParkAndRide (OSMEntity entity) {
        String prValue = entity.getTag("park_ride");
        return prValue != null && ! prValue.equalsIgnoreCase("NO");
    }

    /**
     * TODO Javadoc. What is this for?
     */
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

            ParkRideParking parkRideParking = new ParkRideParking(centerVertex, centroid.y, centroid.x, way);
            parkRideLocationsMap.put(centerVertex, parkRideParking);

            // find nearby edges
            Envelope env = g.getEnvelopeInternal();
            TIntSet nearbyEdges = this.spatialIndex.query(VertexStore.envelopeToFixed(env));

            nearbyEdges.forEach(eidx -> {
                e.seek(eidx);
                // Connect only to edges that are good to link to (This skips tunnels)
                // and skips link edges (that were used to link other stuff)
                if (!e.getFlag(EdgeStore.EdgeFlag.LINKABLE)
                    || e.getFlag(EdgeStore.EdgeFlag.LINK)) {
                    return true;
                }
                LineString edgeGeometry = e.getGeometry();

                // Check every edge found by the spatial index (which may overselect), looking for ones that
                // intersect the road.
                // The intersection of a park and ride linestring and an edge should be one or more points.
                // Potentially a line if the road is running along the edge of the parking lot.
                if (edgeGeometry.intersects(g)) {
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

            ParkRideParking parkRideParking = new ParkRideParking(vidx, node.getLat(), node.getLon(), node);
            parkRideLocationsMap.put(vidx, parkRideParking);

            int targetWalking = getOrCreateVertexNear(node.getLat(), node.getLon(), StreetMode.WALK);
            if (targetWalking == -1) {
                LOG.warn("Could not link park and ride node at ({}, {}) to the street network.",
                        node.getLat(), node.getLon());
                continue;
            }
            EdgeStore.Edge created = edgeStore.addStreetPair(vidx, targetWalking, 1, -1);

            // allow link edges to be traversed by all, access is controlled by connected edges
            created.allowAllModes();
            created.setFlag(EdgeStore.EdgeFlag.LINK);

            // and the back edge
            created.advance();
            created.allowAllModes();
            created.setFlag(EdgeStore.EdgeFlag.LINK);

            int targetDriving = getOrCreateVertexNear(node.getLat(), node.getLon(), StreetMode.CAR);
            //If both CAR and WALK links would connect to the same edge we can skip new useless edge
            if (targetDriving == targetWalking) {
                continue;
            }
            created = edgeStore.addStreetPair(vidx, targetDriving, 1, -1);

            // allow link edges to be traversed by all, access is controlled by connected edges
            created.allowAllModes();
            created.setFlag(EdgeStore.EdgeFlag.LINK);

            // and the back edge
            created.advance();
            created.allowAllModes();
            created.setFlag(EdgeStore.EdgeFlag.LINK);
        }
    }

    /** Connect a park and ride vertex to the street network at a particular location and edge */
    private void connectParkAndRide (int centerVertex, Coordinate coord, EdgeStore.Edge edge) {
        Split split = Split.findOnEdge(coord.y, coord.x, edge);
        int targetVertex = splitEdge(split);
        EdgeStore.Edge created = edgeStore.addStreetPair(centerVertex, targetVertex, 1, -1); // basically free to enter/leave P&R for now.
        // allow link edges to be traversed by all, access is controlled by connected edges
        created.allowAllModes();
        created.setFlag(EdgeStore.EdgeFlag.LINK);

        // and the back edge
        created.advance();
        created.allowAllModes();
        created.setFlag(EdgeStore.EdgeFlag.LINK);
    }

    /**
     * Given a turn restriction relation from OSM, find the affected edges in our street layer and create a turn
     * restriction object to store this information. The restriction is added to the network and associated with
     * the edges it affects, so the method need not return anything.
     *
     * @param osmRelationId the OSM ID of the supplied turn restriction relation
     * @param restrictionRelation a turn restriction relation from OSM
     */
    private void applyTurnRestriction (long osmRelationId, Relation restrictionRelation) {

        // If true, this is an "only" turn restriction rather than a "no" turn restriction, as in
        // "right turn only" rather than "no right turn".
        boolean only;

        if (!restrictionRelation.hasTag("restriction")) {
            // TODO shouldn't this just be an assertion, checking for bugs?
            LOG.error("Restriction {} has no restriction tag, skipping", osmRelationId);
            return;
        }

        if (restrictionRelation.getTag("restriction").startsWith("no_")) only = false;
        else if (restrictionRelation.getTag("restriction").startsWith("only_")) only = true;
        else {
            LOG.error("Restriction {} has invalid restriction tag {}, skipping", osmRelationId, restrictionRelation.getTag("restriction"));
            return;
        }

        TurnRestriction restriction = new TurnRestriction();
        restriction.only = only;

        // Sort out the members of the relation (using relation roles: from, to, via)
        Relation.Member from = null, to = null;
        List<Relation.Member> via = new ArrayList<>();

        for (Relation.Member member : restrictionRelation.members) {
            if ("from".equals(member.role)) {
                if (from != null) {
                    LOG.error("Turn restriction {} has multiple 'from' members, skipping.", osmRelationId);
                    return;
                }
                if (member.type != OSMEntity.Type.WAY) {
                    LOG.error("Turn restriction {} has a 'from' member that is not a way, skipping.", osmRelationId);
                    return;
                }
                from = member;
            }
            else if ("to".equals(member.role)) {
                if (to != null) {
                    LOG.error("Turn restriction {} has multiple 'to' members, skipping.", osmRelationId);
                    return;
                }
                if (member.type != OSMEntity.Type.WAY) {
                    LOG.error("Turn restriction {} has a 'to' member that is not a way, skipping.", osmRelationId);
                    return;
                }
                to = member;
            }
            else if ("via".equals(member.role)) {
                via.add(member);
            }
            // Osmosis may produce situations where referential integrity is violated, probably at the edge of the
            // bounding box where half a turn restriction is outside the box.
            if (member.type == OSMEntity.Type.WAY) {
                if (!osm.ways.containsKey(member.id)) {
                    LOG.warn("Turn restriction relation {} references nonexistent way {}, dropping this relation",
                            osmRelationId,
                            member.id);
                    return;
                }
            } else if (member.type == OSMEntity.Type.NODE) {
                if (!osm.nodes.containsKey(member.id)) {
                    LOG.warn("Turn restriction relation {} references nonexistent node {}, dropping this relation",
                            osmRelationId,
                            member.id);
                    return;
                }
            }
        }

        if (from == null || to == null || via.isEmpty()) {
            LOG.error("Invalid turn restriction {}, does not have from, to and via, skipping", osmRelationId);
            return;
        }

        boolean hasViaWays = false, hasViaNodes = false;

        for (Relation.Member m : via) {
            if (m.type == OSMEntity.Type.WAY) hasViaWays = true;
            else if (m.type == OSMEntity.Type.NODE) hasViaNodes = true;
            else {
                LOG.error("via must be node or way, skipping restriction {}", osmRelationId);
                return;
            }
        }

        if ((hasViaWays && hasViaNodes) || (hasViaNodes && via.size() > 1)) {
            LOG.error("via must be single node or one or more ways, skipping restriction {}", osmRelationId);
            return;
        }

        EdgeStore.Edge e = edgeStore.getCursor();

        if (hasViaNodes) {
            // Turn restriction passes via a single node. This is a fairly simple turn restriction.
            // First find the street layer vertex for the OSM node the restriction passes through.
            int vertex = vertexIndexForOsmNode.get(via.get(0).id);
            if (vertex == -1) {
                LOG.warn("Vertex {} not found to use as via node for restriction {}, skipping this restriction", via.get(0).id, osmRelationId);
                return;
            }
            // use array to dodge Java closure "effectively final" nonsense
            final int[] fromEdge = new int[] { -1 };
            final long fromWayId = from.id; // more "effectively final" nonsense
            final boolean[] bad = new boolean[] { false };
            // find the street layer edge corresponding to the turn restriction's "from" OSM way
            incomingEdges.get(vertex).forEach(eidx -> {
                e.seek(eidx);
                if (e.getOSMID() == fromWayId) {
                    if (fromEdge[0] != -1) {
                        LOG.error("From way enters vertex {} twice, restriction {} is therefore ambiguous, skipping", vertex, osmRelationId);
                        bad[0] = true;
                        return false;
                    }

                    fromEdge[0] = eidx;
                }
                return true; // iteration should continue
            });

            // find the street layer edge corresponding to the turn restriction's "to" OSM way
            final int[] toEdge = new int[] { -1 };
            final long toWayId = to.id; // more effectively final nonsense
            outgoingEdges.get(vertex).forEach(eidx -> {
                e.seek(eidx);
                if (e.getOSMID() == toWayId) {
                    if (toEdge[0] != -1) {
                        LOG.error("To way exits vertex {} twice, restriction {} is therefore ambiguous, skipping", vertex, osmRelationId);
                        bad[0] = true;
                        return false;
                    }

                    toEdge[0] = eidx;
                }

                return true; // iteration should continue
            });

            if (bad[0]) return; // log message already printed

            if (fromEdge[0] == -1 || toEdge[0] == -1) {
                LOG.warn("Did not find from/to edges for restriction {}, skipping", osmRelationId);
                return;
            }

            // phew. create the restriction and apply it where needed
            restriction.fromEdge = fromEdge[0];
            restriction.toEdge = toEdge[0];

            int newRestrictionIndex = turnRestrictions.size();
            turnRestrictions.add(restriction);
            edgeStore.turnRestrictions.put(restriction.fromEdge, newRestrictionIndex);
            addReverseTurnRestriction(restriction, newRestrictionIndex);
        } else {
            // The restriction's via member(s) are ways, which is more tricky than a restriction via a single node.
            Way fromWay = osm.ways.get(from.id);
            long[][] viaNodes = via.stream().map(m -> osm.ways.get(m.id).nodes).toArray(i -> new long[i][]);
            Way toWay = osm.ways.get(to.id);

            // We need to convert from an unordered set of OSM ways to an ordered sequence of R5 edges, where the
            // edges may be smaller than the ways. We do a search, finding a path through our street graph that touches
            // all the "via" OSM ways.
            List<long[]> nodes = new ArrayList<>();
            List<long[]> ways = new ArrayList<>();

            // Initialize search, which will begin at all vertices within the "from" OSM way.
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

                            // We don't handle complicated cases (which may or may not exist) that have loops.
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

            // We have found all possibles paths from the "from" way that use all the via ways.
            // Now, filter those paths to just ones that actually reach the "to" way.
            long[] pathNodes = null; // the sequence of OSM node IDs that you pass through, in order
            long[] pathWays = null; // the sequence of OSM way IDs that you pass through, in order

            for (int statePos = 0; statePos < nodes.size(); statePos++) {
                long[] theseWays = ways.get(statePos);
                Way finalWay = osm.ways.get(theseWays[theseWays.length - 1]);

                for (long node : finalWay.nodes) {
                    for (long toNode : toWay.nodes) {
                        if (node == toNode) {
                            if (pathNodes != null) {
                                LOG.error("Turn restriction {} has ambiguous via ways (multiple paths through via ways between from and to), skipping", osmRelationId);
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
                LOG.error("Invalid turn restriction {}, no way from from to to via via, skipping", osmRelationId);
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
                        LOG.error("From way enters vertex {} twice, restriction {} is therefore ambiguous, skipping", fromVertex, osmRelationId);
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
                        LOG.error("To way exits vertex {} twice, restriction {} is therefore ambiguous, skipping", toVertex, osmRelationId);
                        bad[0] = true;
                        return false;
                    }

                    toEdge[0] = eidx;
                }

                return true; // iteration should continue
            });

            if (bad[0]) return; // log message already printed

            if (fromEdge[0] == -1 || toEdge[0] == -1) {
                LOG.error("Did not find from/to edges for restriction {}, skipping", osmRelationId);
                return;
            }

            restriction.fromEdge = fromEdge[0];
            restriction.toEdge = toEdge[0];

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
                            LOG.error("To way exits vertex {} twice, restriction {} is therefore ambiguous, skipping", vertex, osmRelationId);
                            bad[0] = true;
                            return false;
                        }

                        edge[0] = eidx;
                    }

                    return true; // iteration should continue
                });

                if (bad[0]) return; // log message already printed
                if (edge[0] == -1) {
                    LOG.warn("Did not find via way {} for restriction {}, skipping", wayId, osmRelationId);
                    return;
                }

                affectedEdges.add(edge[0]);
            }

            affectedEdges.reverse();

            restriction.viaEdges = affectedEdges.toArray();

            int index = turnRestrictions.size();
            turnRestrictions.add(restriction);
            edgeStore.turnRestrictions.put(restriction.fromEdge, index);
            addReverseTurnRestriction(restriction, index);

            // take a deep breath
        }
    }

    /**
     * There is support for both "only" and "no" turn restrictions in the forward search direction.
     * "only" restrictions are a mess to implement in reverse, so "only" restrictions are converted to (possibly
     * multiple) "no" restrictions when they're reversed.
     *
     * These converted restrictions are added to StreetLayer.turnRestrictions (the list of all turn restrictions in
     * the network) and then to EdgeStore.turnRestrictionsReverse (associating them with their toEdge).
     *
     * "No" turn restrictions, whether they were originally "no" restrictions or were converted from "only" turn
     * restrictions, are associated with their toEdge (instead of fromEdge) since this method is handling restrictions
     * to be used in reverse searches.
     */
    void addReverseTurnRestriction(TurnRestriction turnRestriction, int index) {
        if (turnRestriction.only) {
            // From "only" turn restrictions, create multiple equivalent "no" turn restrictions.
            List<TurnRestriction> remapped = turnRestriction.remap(this);
            for (TurnRestriction remapped_restriction: remapped) {
                index = turnRestrictions.size();
                turnRestrictions.add(remapped_restriction);
                edgeStore.turnRestrictionsReverse.put(remapped_restriction.toEdge, index);
            }
        } else {
            edgeStore.turnRestrictionsReverse.put(turnRestriction.toEdge, index);
        }
    }

    /**
     * Get or create mapping from a global long OSM ID to an internal street vertex ID, creating the vertex as needed.
     * @return the internal ID for the street vertex that was found or created, or -1 if there was no such OSM node.
     */
    private int getVertexIndexForOsmNode(long osmNodeId) {
        int vertexIndex = vertexIndexForOsmNode.get(osmNodeId);
        if (vertexIndex == -1) {
            // Register a new vertex, incrementing the index starting from zero.
            // Store node coordinates for this new street vertex
            Node node = osm.nodes.get(osmNodeId);
            if (node == null) {
                LOG.warn("OSM data references an undefined node. This is often the result of extracting a bounding box in Osmosis without the completeWays option.");
            } else {
                vertexIndex = vertexStore.addVertex(node.getLat(), node.getLon());
                VertexStore.Vertex v = vertexStore.getCursor(vertexIndex);
                if (node.hasTag("highway", "traffic_signals"))
                    v.setFlag(VertexStore.VertexFlag.TRAFFIC_SIGNAL);
                vertexIndexForOsmNode.put(osmNodeId, vertexIndex);
            }
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
    private void makeEdgePair (Way way, int beginIdx, int endIdx, Long osmID) {

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
            if (node == null) {
                LOG.warn("Not creating street segment that references an undefined node.");
                return;
            }
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
        short forwardSpeed = speedToShort(speedLabeler.getSpeedMS(way, false));
        short backwardSpeed = speedToShort(speedLabeler.getSpeedMS(way, true));

        RoadPermission roadPermission = permissionLabeler.getPermissions(way);

        // Create and store the forward and backward edge
        // FIXME these sets of flags should probably not leak outside the permissions/stress/etc. labeler methods
        EnumSet<EdgeStore.EdgeFlag> forwardFlags = roadPermission.forward;
        EnumSet<EdgeStore.EdgeFlag> backFlags = roadPermission.backward;

        // Doesn't insert edges which don't have any permissions forward and backward
        if (Collections.disjoint(forwardFlags, ALL_PERMISSIONS) && Collections.disjoint(backFlags, ALL_PERMISSIONS)) {
            LOG.debug("Way has no permissions skipping!");
            return;
        }

        // Set forward and backward edge flags from OSM Way tags. The flags will later be stored in the EdgeStore.
        stressLabeler.label(way, forwardFlags, backFlags);
        typeOfEdgeLabeler.label(way, forwardFlags, backFlags);

        Edge newEdge = edgeStore.addStreetPair(beginVertexIndex, endVertexIndex, edgeLengthMillimeters, osmID);
        // newEdge is first pointing to the forward edge in the pair.
        // Geometries apply to both edges in a pair.
        newEdge.setGeometry(nodes);
        // If per-edge traversal time factors are being recorded for this StreetLayer, store these factors for the
        // pair of newly created edges based on the current OSM Way.
        // NOTE the unusual requirement here that each OSM way is exactly one routable network edge.
        if (edgeStore.edgeTraversalTimes != null) {
            try {
                edgeStore.edgeTraversalTimes.setEdgePair(newEdge.edgeIndex, way);
            } catch (Exception ex) {
                LOG.error("Continuing to load but ignoring generalized costs due to exception: {}", ex.toString());
                edgeStore.edgeTraversalTimes = null;
            }
        }

        newEdge.setFlags(forwardFlags);
        newEdge.setSpeed(forwardSpeed);
        // Step ahead to the backward edge in the same pair.
        newEdge.advance();
        newEdge.setFlags(backFlags);
        newEdge.setSpeed(backwardSpeed);

    }

    public void indexStreets () {
        LOG.info("Indexing streets...");
        spatialIndex = new IntHashGrid();
        // Skip by twos, we only need to index forward (even) edges. Their odd companions have the same geometry.
        Edge edge = edgeStore.getCursor();
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
     * FIXME Document whether we're putting all edges, or just forward edges into this index.
     *
     * @param envelope FIXME IN WHAT UNITS, FIXED OR FLOATING?
     */
    public TIntSet findEdgesInEnvelope (Envelope envelope) {
        TIntSet candidates = spatialIndex.query(envelope);
        // Include temporary edges
        if (temporaryEdgeIndex != null) {
            TIntSet temporaryCandidates = temporaryEdgeIndex.query(envelope);
            candidates.addAll(temporaryCandidates);
        }

        // Remove any edges that were temporarily deleted in a scenario.
        // This allows properly re-splitting the same edge in multiple places.
        if (edgeStore.temporarilyDeletedEdges != null) {
            candidates.removeAll(edgeStore.temporarilyDeletedEdges);
        }
        return candidates;
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
        Edge edge = edgeStore.getCursor();
        while (edge.advance()) {
            outgoingEdges.get(edge.getFromVertex()).add(edge.edgeIndex);
            incomingEdges.get(edge.getToVertex()).add(edge.edgeIndex);
        }
        LOG.info("Done building edge lists.");
    }

    /**
     * Find an existing street vertex near the supplied coordinates, or create a new one if there are no vertices
     * near enough. Note that calling this method is potentially destructive (it can modify the street network).
     *
     * This uses {@link #findSplit(double, double, double, StreetMode)} and {@link Split} which require the spatial
     * index to already be built. In other works {@link #indexStreets()} needs to be called before this is used.
     *
     * TODO potential refactor: rename this method Split.perform(), and store a ref to streetLayer in Split.
     * @param lat latitude in floating point geographic (not fixed point) degrees.
     * @param lon longitude in floating point geographic (not fixed point) degrees.
     * @param streetMode Link to edges which have permission for StreetMode
     * @return the index of a street vertex very close to the supplied location,
     *         or -1 if no such vertex could be found or created.
     */
    public int getOrCreateVertexNear(double lat, double lon, StreetMode streetMode) {

        Split split = findSplit(lat, lon, LINK_RADIUS_METERS, streetMode);
        if (split == null) {
            // No linking site was found within range.
            return -1;
        }

        // We have a linking site on a street edge. Find or make a suitable vertex at that site.
        // It is not necessary to reuse the Edge cursor object created inside the findSplit call,
        // one additional object instantiation is harmless.
        Edge edge = edgeStore.getCursor(split.edge);

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
        int newVertexIndex = vertexStore.addVertexFixed((int) split.fixedLat, (int) split.fixedLon);
        int oldToVertex = edge.getToVertex(); // Hold a copy of the to vertex index, because it may be modified below.
        P2<int[]> geoms = edge.splitGeometryAfter(split.seg);
        if (edge.isMutable()) {
            // The edge we are going to split is mutable.
            // We're either building a baseline graph, or modifying an edge created within the same scenario.
            // Modify the existing bidirectional edge pair to serve as the first segment leading up to the split point.
            // Its spatial index entry is still valid, since the edge's envelope will only shrink.
            edge.setLengthMm(split.distance0_mm);
            edge.setToVertex(newVertexIndex);
            edge.setGeometry(geoms.a);
        } else {
            // The edge we are going to split is immutable, and should be left as-is.
            // We must be applying a scenario, and this edge is part of the baseline graph shared between threads.
            // Preserve the existing edge pair, creating a new edge pair to lead up to the split.
            // The new edge will be added to the edge lists later (the edge lists are a transient index).
            // We add it to a temporary spatial index specific to this scenario, rather than the base spatial index
            // which is shared between all scenarios on this network.
            EdgeStore.Edge newEdge0 = edgeStore.addStreetPair(edge.getFromVertex(), newVertexIndex, split.distance0_mm, edge.getOSMID());
            // Copy the flags and speeds for both directions, making the new edge like the existing one.
            newEdge0.copyPairFlagsAndSpeeds(edge);
            newEdge0.setGeometry(geoms.a);
            // Add the new edges to a temporary spatial index that is associated with only this scenario.
            // we need to build this on the fly so that it is possible to split a street multiple times; otherwise,
            // once a street had been split once, the original edge would be removed from consideration
            // (StreetLayer#getEdgesNear filters out edges that have been deleted) and the new edge would not yet be in
            // the spatial index for consideration. Havoc would ensue.
            temporaryEdgeIndex.insert(newEdge0.getEnvelope(), newEdge0.edgeIndex);

            // Exclude the original split edge from all future spatial index queries on this scenario copy.
            // This should allow proper re-splitting of a single edge for multiple new transit stops.
            edgeStore.temporarilyDeletedEdges.add(edge.edgeIndex);
        }
        // Make a new bidirectional edge pair for the segment after the split.
        // The new edge will be added to the edge lists later (the edge lists are a transient index).
        EdgeStore.Edge newEdge1 = edgeStore.addStreetPair(newVertexIndex, oldToVertex, split.distance1_mm, edge.getOSMID());
        // Copy the flags and speeds for both directions, making newEdge1 like the existing edge.
        newEdge1.copyPairFlagsAndSpeeds(edge);
        newEdge1.setGeometry(geoms.b);

        // Insert the new edge into the spatial index
        if (!edgeStore.isExtendOnlyCopy()) {
            spatialIndex.insert(newEdge1.getEnvelope(), newEdge1.edgeIndex);
        } else {
            temporaryEdgeIndex.insert(newEdge1.getEnvelope(), newEdge1.edgeIndex);
        }

        // FIXME Don't allow the router to make U-turns at splitter vertices.
        // One way to do this: make a vertex flag for splitter vertices. When at a splitter vertex, don't consider
        // traversing edges that have the same OSM ID but the opposite direction (i.e. are even when the previous
        // edge was odd or vice versa).

        // Return the splitter vertex ID
        return newVertexIndex;
    }

    // By convention we only index the forward edge since both edges in a pair have the same geometry.
    // TODO expand to handle temp / non-temp automatically? This indexing can't be done at the end to allow re-splitting.
    // TODO add to index automatically when creating edge pair?
    public void indexTemporaryEdgePair (Edge tempEdge) {
        temporaryEdgeIndex.insert(tempEdge.getEnvelope(), tempEdge.edgeIndex);
    }

    /**
     * Perform destructive splitting of edges
     * FIXME: currently used only in P+R. This methods should probably be changed to resuse code from getOrCreateVertexNear.
     */
    public int splitEdge(Split split) {
        // We have a linking site. Find or make a suitable vertex at that site.
        // Retaining the original Edge cursor object inside findSplit is not necessary, one object creation is harmless.
        Edge edge = edgeStore.getCursor(split.edge);

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
        int newVertexIndex = vertexStore.addVertexFixed((int)split.fixedLat, (int)split.fixedLon);

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
     * @return the vertex of the newly created vertex at the supplied coordinates.
     */
    public int createAndLinkVertex (double lat, double lon) {
        int stopVertex = vertexStore.addVertex(lat, lon);
        int streetVertexIndex = getOrCreateVertexNear(lat, lon, StreetMode.WALK);
        if (streetVertexIndex == -1) {
            return -1; // Unlinked
        }

        VertexStore.Vertex streetVertex = vertexStore.getCursor(streetVertexIndex);
        int length_mm = (int) (GeometryUtils.distance(lat,lon, streetVertex.getLat(), streetVertex.getLon())*1000);
        // Set OSM way ID is -1 because this edge is not derived from any OSM way.
        Edge e = edgeStore.addStreetPair(stopVertex, streetVertexIndex, length_mm, -1);

        // Allow all modes to traverse street-to-transit link edges.
        // In practice, mode permissions will be controlled by whatever street edges lead up to these link edges.
        e.allowAllModes(); // forward edge
        e.setFlag(EdgeStore.EdgeFlag.LINK);
        e.advance();
        e.allowAllModes(); // backward edge
        e.setFlag(EdgeStore.EdgeFlag.LINK);
        return stopVertex;
    }

    /**
     * Find a location on an existing street near the given point, without actually creating any vertices or edges.
     * This is a nondestructive operation: it simply finds a candidate split point without modifying anything.
     * This function starts with a small search envelope and expands it as needed under the assumption that most
     * search points will be close to a road.
     * TODO favor transit station platforms and pedestrian paths when requested
     * @param lat latitude in floating point geographic coordinates (not fixed point int coordinates)
     * @param lon longitude in floating point geographic coordinates (not fixed point int coordinates)
*      @param streetMode a mode of travel that the street must allow
     * @return a Split object representing a point along a sub-segment of a specific edge, or null if there are no
     *         streets nearby allowing the specified mode of travel.
     */
    public Split findSplit(double lat, double lon, double radiusMeters, StreetMode streetMode) {
        Split split = null;
        // If the specified radius is large, first try a mini-search on the assumption
        // that most linking points are close to roads.
        if (radiusMeters > INITIAL_LINK_RADIUS_METERS) {
            split = Split.find(lat, lon, INITIAL_LINK_RADIUS_METERS, this, streetMode);
        }
        // If no split point was found by the first search (or no search was yet conducted) search with the full radius.
        if (split == null) {
            split = Split.find(lat, lon, radiusMeters, this, streetMode);
        }
        return split;
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
    }

    public int getVertexCount() {
        return vertexStore.getVertexCount();
    }

    public Envelope getEnvelope() {
        return envelope;
    }

    /**
     * We intentionally avoid using clone() on EdgeStore and VertexStore so all field copying is explicit and we can
     * clearly see whether we are accidentally shallow-copying any collections or data structures from the base graph.
     * StreetLayer has a lot more fields and most of them can be shallow-copied, so here we use clone() for convenience.
     * @param willBeModified must be true if the scenario to be applied will make any changes to the new StreetLayer
     *                       copy. This allows some optimizations (the lists in the StreetLayer will not be wrapped).
     * @return a copy of this StreetLayer to which Scenarios can be applied without affecting the original StreetLayer.
     *
     * It's questionable whether the willBeModified optimization actually affects routing speed, but in theory it
     * saves a comparison and an extra dereference every time we use the edge/vertex stores.
     * TODO check whether this actually affects speed. If not, just wrap the lists in every scenario copy.
     * Why would you clone the StreetLayer at all if it's not going to be modified? Because there are circular
     * references between the street and transit layers, so if you don't clone both, you could end up at the wrong
     * transit or street layer by chaining together those references.
     */
    public StreetLayer scenarioCopy(TransportNetwork newScenarioNetwork, boolean willBeModified) {
        StreetLayer copy = this.clone();
        if (willBeModified) {
            // Wrap all the edge and vertex storage in classes that make them extensible.
            // Indicate that the content of the new StreetLayer will be changed by giving it the scenario's scenarioId.
            // If the copy will not be modified, scenarioId remains unchanged to allow cached pointset linkage reuse.
            copy.scenarioId = newScenarioNetwork.scenarioId;
            copy.edgeStore = edgeStore.extendOnlyCopy(copy);
            // The extend-only copy of the EdgeStore also contains a new extend-only copy of the VertexStore.
            copy.vertexStore = copy.edgeStore.vertexStore;
            copy.temporaryEdgeIndex = new IntHashGrid();
        }
        copy.parentNetwork = newScenarioNetwork;
        copy.baseStreetLayer = this;
        return copy;
    }


    /**
     * Creates vertices to represent each bike rental station.
     */
    public void associateBikeSharing(TNBuilderConfig tnBuilderConfig) {
        LOG.info("Builder file:{}", tnBuilderConfig.bikeRentalFile);
        BikeRentalBuilder bikeRentalBuilder = new BikeRentalBuilder(new File(tnBuilderConfig.bikeRentalFile));
        List<BikeRentalStation> bikeRentalStations = bikeRentalBuilder.getRentalStations();
        bikeRentalStationMap = new TIntObjectHashMap<>(bikeRentalStations.size());
        LOG.info("Bike rental stations:{}", bikeRentalStations.size());
        int numAddedStations = 0;
        for (BikeRentalStation bikeRentalStation: bikeRentalStations) {
            int streetVertexIndex = getOrCreateVertexNear(bikeRentalStation.lat, bikeRentalStation.lon, StreetMode.WALK);
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
        LOG.info("Added {} out of {} stations ratio:{}",
            numAddedStations,
            bikeRentalStations.size(),
            numAddedStations/bikeRentalStations.size()
        );
    }

    public StreetLayer clone () {
        try {
            return (StreetLayer) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("This exception cannot happen. This is why I love checked exceptions.");
        }
    }

    /**
     * @return true if this StreetLayer was created by a scenario,
     * and is therefore wrapping a base StreetLayer.
     */
    public boolean isScenarioCopy() {
        return baseStreetLayer != null;
    }

    /**
     * Create a geometry in FIXED POINT DEGREES containing all the points on all edges created or removed by the
     * scenario that produced this StreetLayer, buffered by radiusMeters. This is a MultiPolygon or GeometryCollection.
     * When there are no created or removed edges, returns an empty geometry rather than null, because we test whether
     * transit stops are contained within the resulting geometry.
     */
    public Geometry scenarioEdgesBoundingGeometry(int radiusMeters) {
        List<Polygon> geoms = new ArrayList<>();
        Edge edge = edgeStore.getCursor();
        edgeStore.forEachTemporarilyAddedOrDeletedEdge(e -> {
            edge.seek(e);
            Envelope envelope = edge.getEnvelope();
            GeometryUtils.expandEnvelopeFixed(envelope, radiusMeters);
            geoms.add((Polygon)GeometryUtils.geometryFactory.toGeometry(envelope));
        });
        // We can't just make a multipolygon as the component polygons may not be disjoint. Unions are pretty quick though.
        // The UnaryUnionOp gets its geometryFactory from the geometries it's operating on.
        // We need to supply one in case the list is empty, so it can return an empty geometry instead of null.
        Geometry result = new UnaryUnionOp(geoms, GeometryUtils.geometryFactory).union();
        // logFixedPointGeometry("Unioned buffered streets", result);
        return result;
    }

    /**
     * Create a geometry in FIXED POINT DEGREES containing all the points on all edges created by the scenario that
     * produced this StreetLayer. Returns null if the resulting geometry would contain no points, to allow
     * short-circuiting around slow operations that would use the result.
     */
    public Geometry addedEdgesBoundingGeometry () {
        List<Geometry> geoms = new ArrayList<>();
        Edge edge = edgeStore.getCursor();
        edgeStore.forEachTemporarilyAddedEdge(e -> {
            edge.seek(e);
            // Should we simply only put linkable edges in the temp edge index? We could even name it accordingly.
            // When iterating all scenario edges for egress cost table rebuilding, we don't use the spatial index.
            if (edge.getFlag(EdgeStore.EdgeFlag.LINKABLE)) {
                Envelope envelope = edge.getEnvelope();
                // Note that envelope geometries are not always Polygons:
                // they can collapse to a linestring if they have no size in one dimension.
                geoms.add(GeometryUtils.geometryFactory.toGeometry(envelope));
            }
        });
        // The component polygons may not be disjoint, so rather than making a multipolygon compute the union.
        Geometry result = new UnaryUnionOp(geoms, GeometryUtils.geometryFactory).union();
        if (result.isEmpty()) {
            return null;
        } else {
            return result;
        }
    }

    /**
     * Given a JTS Geometry in fixed-point latitude and longitude, log it as floating-point GeoJSON.
     */
    public static void logFixedPointGeometry (String label, Geometry fixedPointGeometry) {
        if (fixedPointGeometry == null){
            LOG.info("{} is null.", label);
        } else if (fixedPointGeometry.isEmpty()) {
            LOG.info("{} is empty.", label);
        } else {
            String geoJson = new GeometryJSON().toString(fixedDegreeGeometryToFloating(fixedPointGeometry));
            if (geoJson == null) {
                LOG.info("Could not convert non-null geometry to GeoJSON");
            } else {
                LOG.info("{} {}", label, geoJson);
            }
        }
    }

    /**
     * Finds all the P+R stations in given envelope. This might overselect (doesn't filter the objects from the
     * spatial index) but it's only used in visualizations.
     *
     * @param env Envelope in float degrees
     * @return empty list if none are found or no P+R stations are in graph
     */
    public List<ParkRideParking> findParkRidesInEnvelope(Envelope env) {
        List<ParkRideParking> parkingRides = new ArrayList<>();

        if (parkRideLocationsMap != null) {
            EdgeStore.Edge e = edgeStore.getCursor();
            VertexStore.Vertex v = vertexStore.getCursor();
            TIntSet nearbyEdges = spatialIndex.query(VertexStore.envelopeToFixed(env));
            nearbyEdges.forEach(eidx -> {
                e.seek(eidx);
                if (e.getFlag(EdgeStore.EdgeFlag.LINK)) {
                    v.seek(e.getFromVertex());
                    if (v.getFlag(VertexStore.VertexFlag.PARK_AND_RIDE)) {
                        ParkRideParking parkRideParking = parkRideLocationsMap.get(e.getFromVertex());
                        parkingRides.add(parkRideParking);
                    }
                }
                return true;
            });
        }
        return parkingRides;
    }

    /**
     * Finds all the bike share stations in given envelope. Might overselect from the spatial index but this is only
     * used for visualizations.
     *
     * @param env Envelope in float degrees
     * @return BikeRentalStations, or empty list if none are found or there are no bike stations in the graph.
     */
    public List<BikeRentalStation> findBikeSharesInEnvelope(Envelope env) {
        List<BikeRentalStation> bikeRentalStations = new ArrayList<>();

        if (bikeRentalStationMap != null) {
            EdgeStore.Edge e = edgeStore.getCursor();
            VertexStore.Vertex v = vertexStore.getCursor();
            TIntSet nearbyEdges = spatialIndex.query(VertexStore.envelopeToFixed(env));
            nearbyEdges.forEach(eidx -> {
                e.seek(eidx);
                //TODO: for now bikeshares aren't connected with link edges to the graph
                //if (e.getFlag(EdgeStore.EdgeFlag.LINK)) {
                    v.seek(e.getFromVertex());

                    if (v.getFlag(VertexStore.VertexFlag.BIKE_SHARING)) {
                        BikeRentalStation bikeRentalStation = bikeRentalStationMap.get(e.getFromVertex());
                        bikeRentalStations.add(bikeRentalStation);
                    }
                //}
                return true;
            });
        }
        return bikeRentalStations;
    }

    /**
     * For the given location and mode of travel, get an object representing the available on-demand mobility service,
     * including pick-up delay and which stops it will take you to. We currently only support one StreetMode per pickup
     * delay polygon collection. If the supplied mode matches the wait time polygons' mode, return the pickup delay
     * (or -1 for no service). Otherwise, return an object representing a 0 second delay.
     * @param lat latitude of the starting point in floating point degrees
     * @param lon longitude the starting point in floating point degrees
     * @return object with pick-up time and stops served
     */
    public PickupWaitTimes.AccessService getAccessService (double lat, double lon, StreetMode streetMode) {
        if (pickupWaitTimes != null && pickupWaitTimes.streetMode == streetMode) {
            return pickupWaitTimes.getAccessService(lat, lon);
        } else {
            return NO_WAIT_ALL_STOPS;
        }
    }

    public boolean edgeIsDeletedByScenario (int p) {
        return edgeStore.temporarilyDeletedEdges != null && edgeStore.temporarilyDeletedEdges.contains(p);
    }

    public boolean edgeIsAddedByScenario (int p) {
        return this.isScenarioCopy() && p >= edgeStore.firstModifiableEdge;
    }

    @Override
    public String toString() {
        String detail = "(base)";
        if (baseStreetLayer != null) {
            detail = "(scenario " + parentNetwork.scenarioId + ")";
        }
        return "StreetLayer" + detail;
    }

}
