package com.conveyal.r5.point_to_point;

import com.conveyal.r5.analyst.fare.ParetoServer;
import com.conveyal.r5.api.GraphQlRequest;
import com.conveyal.r5.api.util.BikeRentalStation;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.ParkRideParking;
import com.conveyal.r5.api.util.Stop;
import com.conveyal.r5.common.GeoJsonFeature;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.kryo.KryoNetworkSerializer;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.point_to_point.builder.RouterInfo;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.DebugRoutingVisitor;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.TurnRestriction;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import gnu.trove.map.TIntIntMap;
import gnu.trove.set.TIntSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.buffer.OffsetCurveBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;
import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;
import static spark.Spark.before;
import static spark.Spark.get;
import static spark.Spark.post;
import static spark.Spark.options;
import static spark.Spark.port;
import static spark.Spark.staticFileLocation;

/**
 * This will represent point to point search server.
 *
 * It can build point to point TransportNetwork and start a server with API for point to point searches
 *
 */
public class PointToPointRouterServer {
    private static final Logger LOG = LoggerFactory.getLogger(PointToPointRouterServer.class);

    private static final int DEFAULT_PORT = 8080;

    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

    public static final String BUILDER_CONFIG_FILENAME = "build-config.json";

    private static final String USAGE = "It expects --build [path to directory with GTFS and PBF files] to build the graphs\nor --graphs [path to directory with graph] to start the server with provided graph";

    public static final int RADIUS_METERS = 200;

    public static void main(String[] commandArguments) {

        LOG.info("Arguments: {}", Arrays.toString(commandArguments));

        final boolean inMemory = false;

        if ("--build".equals(commandArguments[0])) {

            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }

            TransportNetwork transportNetwork = TransportNetwork.fromDirectory(dir);
            //In memory doesn't save it to disk others do (build, preFlight)
            if (!inMemory) {
                try {
                    KryoNetworkSerializer.write(transportNetwork, new File(dir, "network.dat"));
                } catch (Exception e) {
                    LOG.error("An error occurred during saving transit networks. Exiting.", e);
                    System.exit(-1);
                }
            }
        } else if ("--graphs".equals(commandArguments[0])) {
            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }
            try {
                LOG.info("Loading transit networks from: {}", dir);
                TransportNetwork transportNetwork = KryoNetworkSerializer.read(new File(dir, "network.dat"));
                transportNetwork.readOSM(new File(dir, "osm.mapdb"));
                run(transportNetwork);
            } catch (Exception e) {
                LOG.error("An error occurred during the reading or decoding of transit networks", e);
                System.exit(-1);
            }
        } else if ("--isochrones".equals(commandArguments[0])) {
            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }
            try {
                LOG.info("Loading transit networks from: {}", dir);
                TransportNetwork transportNetwork = KryoNetworkSerializer.read(new File(dir, "network.dat"));
                transportNetwork.readOSM(new File(dir, "osm.mapdb"));
                transportNetwork.transitLayer.buildDistanceTables(null);
                // Build WALK and CAR linked pointsets because they are needed for isochrones (which are enabled).
                transportNetwork.rebuildLinkedGridPointSet(StreetMode.WALK, StreetMode.CAR);
                run(transportNetwork);
            } catch (Exception e) {
                LOG.error("An error occurred during the reading or decoding of transit networks", e);
                System.exit(-1);
            }
        } else if ("--help".equals(commandArguments[0])
                || "-h".equals(commandArguments[0])
                || "--usage".equals(commandArguments[0])
                || "-u".equals(commandArguments[0])) {
            System.out.println(USAGE);
        } else {
            LOG.info("Unknown argument: {}", commandArguments[0]);
            System.out.println(USAGE);
        }

    }

    private static void run(TransportNetwork transportNetwork) {
        port(DEFAULT_PORT);
        ObjectMapper mapper = new ObjectMapper();
        //ObjectReader is a new lightweight mapper which can only deserialize specified class
        ObjectReader graphQlRequestReader = mapper.reader(GraphQlRequest.class);
        ObjectReader mapReader = mapper.reader(HashMap.class);
        staticFileLocation("debug-plan");
        PointToPointQuery pointToPointQuery = new PointToPointQuery(transportNetwork);
        ParetoServer paretoServer = new ParetoServer(transportNetwork);

        // add cors header
        before((req, res) -> res.header("Access-Control-Allow-Origin", "*"));

        options("/*", (request, response) -> {

            String accessControlRequestHeaders = request
                .headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }

            String accessControlRequestMethod = request
                .headers("Access-Control-Request-Method");
            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }

            return "OK";
        });

        get("/metadata", (request, response) -> {
            response.header("Content-Type", "application/json");
            RouterInfo routerInfo = new RouterInfo();
            routerInfo.envelope = transportNetwork.getEnvelope();
            return routerInfo;

        }, JsonUtilities.objectMapper::writeValueAsString);

        get("/reachedStops", (request, response) -> {
            response.header("Content-Type", "application/json");

            Map<String, Object> content = new HashMap<>(2);
            String queryMode = request.queryParams("mode");

            StreetMode streetMode = StreetMode.valueOf(queryMode);
            if (streetMode == null) {
                content.put("errors", "Mode is wrong");
                return content;
            }
            Float fromLat = request.queryMap("fromLat").floatValue();

            Float fromLon = request.queryMap("fromLon").floatValue();

            Map<String, Object> featureCollection = new HashMap<>(2);
            featureCollection.put("type", "FeatureCollection");
            List<GeoJsonFeature> features = new ArrayList<>();
            ProfileRequest profileRequest = new ProfileRequest();
            profileRequest.zoneId = transportNetwork.getTimeZone();
            profileRequest.fromLat = fromLat;
            profileRequest.fromLon = fromLon;
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);

            streetRouter.profileRequest = profileRequest;
            streetRouter.streetMode = streetMode;
            streetRouter.timeLimitSeconds = profileRequest.getMaxTimeSeconds(LegMode.valueOf(streetMode.toString()));
            streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            streetRouter.transitStopSearch = true;
            if(streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
                streetRouter.route();
                streetRouter.getReachedStops().forEachEntry((stopIdx, weight) -> {
                    VertexStore.Vertex stopVertex = transportNetwork.streetLayer.vertexStore.getCursor(
                        transportNetwork.transitLayer.streetVertexForStop.get(stopIdx));
                    StreetRouter.State state = streetRouter.getStateAtVertex(stopVertex.index);
                    GeoJsonFeature feature = new GeoJsonFeature(stopVertex.getLon(), stopVertex.getLat());
                    feature.addProperty("weight", weight);
                    feature.addProperty("name", transportNetwork.transitLayer.stopNames.get(stopIdx));
                    feature.addProperty("type", "stop");
                    feature.addProperty("mode", streetMode.toString());
                    if (state != null) {
                        feature.addProperty("distance_m", state.distance/1000);
                        feature.addProperty("duration_s", state.getDurationSeconds());
                        LOG.info("Duration:{}s diff:{}", state.getDurationSeconds());
                    }
                    features.add(feature);
                    return true;
                });
            } else {
                content.put("errors", "Start point isn't found!");
            }

            LOG.info("Num features:{}", features.size());
            featureCollection.put("features", features);
            content.put("data", featureCollection);

            return content;
        }, JsonUtilities.objectMapper::writeValueAsString);

        get("/reachedBikeShares", (request, response) -> {
            response.header("Content-Type", "application/json");

            Map<String, Object> content = new HashMap<>(2);
            String queryMode = request.queryParams("mode");

            StreetMode streetMode = StreetMode.valueOf(queryMode);
            if (streetMode == null) {
                content.put("errors", "Mode is wrong");
                return content;
            }
            Float fromLat = request.queryMap("fromLat").floatValue();

            Float fromLon = request.queryMap("fromLon").floatValue();

            Map<String, Object> featureCollection = new HashMap<>(2);
            featureCollection.put("type", "FeatureCollection");
            List<GeoJsonFeature> features = new ArrayList<>();
            ProfileRequest profileRequest = new ProfileRequest();
            profileRequest.zoneId = transportNetwork.getTimeZone();
            profileRequest.fromLat = fromLat;
            profileRequest.fromLon = fromLon;
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);

            streetRouter.profileRequest = profileRequest;
            streetRouter.streetMode = streetMode;
            streetRouter.timeLimitSeconds = profileRequest.getMaxTimeSeconds(LegMode.valueOf(streetMode.toString()));
            streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            streetRouter.flagSearch = VertexStore.VertexFlag.BIKE_SHARING;
            streetRouter.flagSearchQuantity = 50;
            if(streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
                streetRouter.route();
                streetRouter.getReachedVertices(VertexStore.VertexFlag.BIKE_SHARING).forEachEntry((vertexIdx, state) -> {
                    VertexStore.Vertex bikeShareVertex = transportNetwork.streetLayer.vertexStore.getCursor(vertexIdx);
                    BikeRentalStation bikeRentalStation = transportNetwork.streetLayer.bikeRentalStationMap.get(vertexIdx);
                    GeoJsonFeature feature = new GeoJsonFeature(bikeShareVertex.getLon(), bikeShareVertex.getLat());
                    if (bikeRentalStation != null) {
                        feature.addProperty("name", bikeRentalStation.name);
                        feature.addProperty("id", bikeRentalStation.id);
                        feature.addProperty("bikes", bikeRentalStation.bikesAvailable);
                        feature.addProperty("places", bikeRentalStation.spacesAvailable);
                    }
                    feature.addProperty("type", "bike_share");
                    if (state != null) {
                        feature.addProperty("distance_m", state.distance/1000);
                    }
                    features.add(feature);
                    return true;
                });
            } else {
                content.put("errors", "Start point isn't found!");
            }

            LOG.info("Num features:{}", features.size());
            featureCollection.put("features", features);
            content.put("data", featureCollection);

            return content;
        }, JsonUtilities.objectMapper::writeValueAsString);


        get("/reachedParkRide", (request, response) -> {
            response.header("Content-Type", "application/json");

            StreetMode streetMode = StreetMode.CAR;
            Map<String, Object> content = new HashMap<>(2);
            Float fromLat = request.queryMap("fromLat").floatValue();

            Float fromLon = request.queryMap("fromLon").floatValue();

            Map<String, Object> featureCollection = new HashMap<>(2);
            featureCollection.put("type", "FeatureCollection");
            List<GeoJsonFeature> features = new ArrayList<>();
            ProfileRequest profileRequest = new ProfileRequest();
            profileRequest.zoneId = transportNetwork.getTimeZone();
            profileRequest.fromLat = fromLat;
            profileRequest.fromLon = fromLon;
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);

            streetRouter.profileRequest = profileRequest;
            streetRouter.streetMode = streetMode;
            streetRouter.timeLimitSeconds = profileRequest.getMaxTimeSeconds(LegMode.valueOf(streetMode.toString()));
            streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            streetRouter.flagSearch = VertexStore.VertexFlag.PARK_AND_RIDE;
            streetRouter.flagSearchQuantity = 50;
            if(streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
                streetRouter.route();
                streetRouter.getReachedVertices(VertexStore.VertexFlag.PARK_AND_RIDE).forEachEntry((vertexIdx, state) -> {
                    VertexStore.Vertex stopVertex = transportNetwork.streetLayer.vertexStore.getCursor(vertexIdx);
                    GeoJsonFeature feature = new GeoJsonFeature(stopVertex.getLon(), stopVertex.getLat());
                    //feature.addProperty("name", transportNetwork.transitLayer.stopNames.get(stopIdx));
                    feature.addProperty("type", "park_ride");
                    feature.addProperty("mode", "CAR");
                    feature.addProperty("distance_m", state.distance/1000);
                    feature.addProperty("duration_s", state.getDurationSeconds());
                    features.add(feature);
                    return true;
                });
            } else {
                content.put("errors", "Start point isn't found!");
            }

            LOG.info("Num features:{}", features.size());
            featureCollection.put("features", features);
            content.put("data", featureCollection);

            return content;
        }, JsonUtilities.objectMapper::writeValueAsString);

        get("/plan", (request, response) -> {
            response.header("Content-Type", "application/json");
            Map<String, Object> content = new HashMap<>();
            String queryMode = request.queryParams("mode");

            StreetMode streetMode = StreetMode.valueOf(queryMode);
            if (streetMode == null) {
                content.put("errors", "Mode is wrong");
                return content;
            }
            Float fromLat = request.queryMap("fromLat").floatValue();
            Float toLat = request.queryMap("toLat").floatValue();

            Float fromLon = request.queryMap("fromLon").floatValue();
            Float toLon = request.queryMap("toLon").floatValue();
            //TODO errorchecks

            Boolean fullStateList = request.queryMap("full").booleanValue();
            DebugRoutingVisitor debugRoutingVisitor = new DebugRoutingVisitor(transportNetwork.streetLayer.edgeStore);

            if (fullStateList == null) {
                fullStateList = false;
            }

            ProfileRequest profileRequest = new ProfileRequest();
            Boolean reverseSearch = request.queryMap("reverse").booleanValue();
            if (reverseSearch != null && reverseSearch) {
                profileRequest.reverseSearch = true;
            }
            profileRequest.zoneId = transportNetwork.getTimeZone();
            profileRequest.fromLat = fromLat;
            profileRequest.fromLon = fromLon;
            profileRequest.toLat = toLat;
            profileRequest.toLon = toLon;
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);

            streetRouter.profileRequest = profileRequest;
            streetRouter.streetMode = streetMode;

            // TODO use target pruning instead of a distance limit
            streetRouter.distanceLimitMeters = 100_000;
            //Split for end coordinate
            if (!streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)) {
                content.put("errors", "Edge near the end coordinate wasn't found. Routing didn't start!");
                return content;
            }
            if (!streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
                content.put("errors", "Edge near the origin coordinate wasn't found. Routing didn't start!");
                return content;
            }
            if (fullStateList) {
                streetRouter.setRoutingVisitor(debugRoutingVisitor);
            }

            streetRouter.route();
            TIntIntMap stops = streetRouter.getReachedStops();

            if (fullStateList) {
                Map<String, Object> featureCollection = new HashMap<>(2);
                featureCollection.put("type", "FeatureCollection");
                List<GeoJsonFeature> features = debugRoutingVisitor.getFeatures();

                LOG.info("Num features:{}", features.size());
                featureCollection.put("features", features);
                content.put("data", featureCollection);
                return content;
            }

            //Gets lowest weight state for end coordinate split
            StreetRouter.State lastState = streetRouter.getState(streetRouter.getDestinationSplit());
//          StreetRouter.State lastState = streetRouter.getState(transportNetwork.transitLayer.streetVertexForStop.get(stops.keys()[0]));
            if (lastState != null) {
                Map<String, Object> featureCollection = new HashMap<>(2);
                featureCollection.put("type", "FeatureCollection");
                List<GeoJsonFeature> features = new ArrayList<>();

                fillFeature(transportNetwork, lastState, features, profileRequest.reverseSearch);
                featureCollection.put("features", features);
                content.put("data", featureCollection);
            } else {
                Split destinationSplit = streetRouter.getDestinationSplit();
                //FIXME: why are coordinates of vertex0 and vertex1 the same
                //but distance to vertex and vertex idx differ

                /*Path wasn't found we return 3 points:
                 * - Point on start of edge that we started the search
                 * - Point on end of edge that started the search
                 * - Closest point on edge to start coordinate
                 *
                 * We also return incoming edges to end points of edge that should end the search
                 */
                Map<String, Object> featureCollection = new HashMap<>(2);
                featureCollection.put("type", "FeatureCollection");
                List<GeoJsonFeature> features = new ArrayList<>(10);
                GeoJsonFeature feature = new GeoJsonFeature(
                    fixedDegreesToFloating(destinationSplit.fixedLon), fixedDegreesToFloating(destinationSplit.fixedLat));
                feature.addProperty("type", "Point on edge");

                features.add(feature);

                VertexStore.Vertex vertex = transportNetwork.streetLayer.vertexStore.getCursor(destinationSplit.vertex0);
                feature = new GeoJsonFeature(vertex.getLon(), vertex.getLat());
                feature.addProperty("type", "Edge start point");
                feature.addProperty("vertex_idx", destinationSplit.vertex0);
                feature.addProperty("distance_to_vertex", destinationSplit.distance0_mm/1000);
                features.add(feature);
                transportNetwork.streetLayer.incomingEdges.get(destinationSplit.vertex0)
                    .forEach(edge_idx -> {
                        EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor(edge_idx);
                        GeoJsonFeature edge_feature = new GeoJsonFeature(edge.getGeometry());
                        edge_feature.addProperty("idx", edge_idx);
                        edge_feature.addProperty("permissions", edge.getPermissionsAsString());
                        edge_feature.addProperty("to", "vertex0");
                        features.add(edge_feature);
                        return true;
                    });

                transportNetwork.streetLayer.vertexStore.getCursor(destinationSplit.vertex1);
                feature = new GeoJsonFeature(vertex.getLon(), vertex.getLat());
                feature.addProperty("type", "Edge end point");
                feature.addProperty("vertex_idx", destinationSplit.vertex1);
                feature.addProperty("distance_to_vertex", destinationSplit.distance1_mm/1000);
                features.add(feature);
                transportNetwork.streetLayer.incomingEdges.get(destinationSplit.vertex1)
                    .forEach(edge_idx -> {
                        EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor(edge_idx);
                        GeoJsonFeature edge_feature = new GeoJsonFeature(edge.getGeometry());
                        edge_feature.addProperty("idx", edge_idx);
                        edge_feature.addProperty("permissions", edge.getPermissionsAsString());
                        edge_feature.addProperty("to", "vertex1");
                        features.add(edge_feature);
                        return true;
                    });

                featureCollection.put("features", features);
                content.put("data", featureCollection);
                content.put("errors", "Path to end coordinate wasn't found!");
            }
            return content;
        }, JsonUtilities.objectMapper::writeValueAsString);

        get("/seenStops", (request, response) -> {
            response.header("Content-Type", "application/json");
            if (request.queryParams().size() < 4) {
                response.status(400);
                return "";
            }
            float north = request.queryMap("n").floatValue();
            float south = request.queryMap("s").floatValue();
            float east = request.queryMap("e").floatValue();
            float west = request.queryMap("w").floatValue();

            Envelope env = new Envelope(east, west,
                south, north);

            // write geojson to response
            Map<String, Object> featureCollection = new HashMap<>(2);
            featureCollection.put("type", "FeatureCollection");

            Collection<Stop> stops = transportNetwork.transitLayer.findApiStopsInEnvelope(env);
            List<GeoJsonFeature> features = new ArrayList<>(stops.size());

            stops.forEach(stop -> {
                GeoJsonFeature feature = new GeoJsonFeature(stop.lon, stop.lat);
                feature.addProperty("name", stop.name);
                feature.addProperty("stopId", stop.stopId);
                feature.addProperty("mode", stop.mode);
                features.add(feature);
            });

            //LOG.info("Found {} stops", features.size());
            featureCollection.put("features", features);

            return featureCollection;
        }, JsonUtilities.objectMapper::writeValueAsString);

        get("/seenParkRides", (request, response) -> {
            response.header("Content-Type", "application/json");
            if (request.queryParams().size() < 4) {
                response.status(400);
                return "";
            }
            float north = request.queryMap("n").floatValue();
            float south = request.queryMap("s").floatValue();
            float east = request.queryMap("e").floatValue();
            float west = request.queryMap("w").floatValue();

            Envelope env = new Envelope(east, west,
                south, north);

            // write geojson to response
            Map<String, Object> featureCollection = new HashMap<>(2);
            featureCollection.put("type", "FeatureCollection");

            List<ParkRideParking> parkRideParkings = transportNetwork.streetLayer.findParkRidesInEnvelope(env);
            List<GeoJsonFeature> features = new ArrayList<>(parkRideParkings.size());

            parkRideParkings.forEach(parkRideParking -> {
                GeoJsonFeature feature = new GeoJsonFeature(parkRideParking.lon, parkRideParking.lat);
                feature.addProperty("type", "P+R");
                feature.addProperty("name", parkRideParking.name);
                feature.addProperty("id", parkRideParking.id);
                feature.addProperty("capacity", parkRideParking.capacity);
                features.add(feature);
            });

            //LOG.info("Found {} ParkRides", features.size());
            featureCollection.put("features", features);

            return featureCollection;
        }, JsonUtilities.objectMapper::writeValueAsString);

        get("/seenBikeShares", (request, response) -> {
            response.header("Content-Type", "application/json");
            if (request.queryParams().size() < 4) {
                response.status(400);
                return "";
            }
            float north = request.queryMap("n").floatValue();
            float south = request.queryMap("s").floatValue();
            float east = request.queryMap("e").floatValue();
            float west = request.queryMap("w").floatValue();

            Envelope env = new Envelope(east, west,
                south, north);

            // write geojson to response
            Map<String, Object> featureCollection = new HashMap<>(2);
            featureCollection.put("type", "FeatureCollection");

            List<BikeRentalStation> bikeRentalStations = transportNetwork.streetLayer.findBikeSharesInEnvelope(env);
            List<GeoJsonFeature> features = new ArrayList<>(bikeRentalStations.size());

            bikeRentalStations.forEach(bikeRentalStation -> {
                GeoJsonFeature feature = new GeoJsonFeature(bikeRentalStation.lon, bikeRentalStation.lat);
                feature.addProperty("type", "Bike share");
                feature.addProperty("name", bikeRentalStation.name);
                feature.addProperty("id", bikeRentalStation.id);
                features.add(feature);
            });

            //LOG.info("Found {} bike shares", features.size());
            featureCollection.put("features", features);

            return featureCollection;
        }, JsonUtilities.objectMapper::writeValueAsString);

        get("debug/turns", (request, response) -> {
            response.header("Content-Type", "application/json");
            if (request.queryParams().size() < 4) {
                response.status(400);
                return "";
            }
            float north = request.queryMap("n").floatValue();
            float south = request.queryMap("s").floatValue();
            float east = request.queryMap("e").floatValue();
            float west = request.queryMap("w").floatValue();
            Boolean iboth = request.queryMap("both").booleanValue();
            Boolean idetail = request.queryMap("detail").booleanValue();
            boolean both, detail;

            if (iboth == null) {
                both = false;
            } else {
                both = iboth;
            }

            if (idetail == null) {
                detail = false;
            } else {
                detail = idetail;
            }

            String layer = "turns"; // request.params(":layer");

            Envelope env = new Envelope(floatingDegreesToFixed(east), floatingDegreesToFixed(west),
                floatingDegreesToFixed(south), floatingDegreesToFixed(north));
            TIntSet streets = transportNetwork.streetLayer.findEdgesInEnvelope(env);

            if (streets.size() > 100_000) {
                LOG.warn("Refusing to include more than 100,000 edges in result");
                response.status(401);
                return "";
            }

            // write geojson to response
            Map<String, Object> featureCollection = new HashMap<>(2);
            featureCollection.put("type", "FeatureCollection");
            List<GeoJsonFeature> features = new ArrayList<>(streets.size());

            EdgeStore.Edge cursor = transportNetwork.streetLayer.edgeStore.getCursor();

            BufferParameters bufParams = new BufferParameters();
            bufParams.setSingleSided(true);
            bufParams.setJoinStyle(BufferParameters.JOIN_BEVEL);
            OffsetCurveBuilder offsetBuilder = new OffsetCurveBuilder(new PrecisionModel(),
                bufParams);
            float distance = -0.00005f;

            Set<Integer> seenVertices = new HashSet<>(streets.size());

            if ("turns".equals(layer)) {
                streets.forEach(s -> {
                    try {
                        int edgeIdx = s;
                        makeTurnEdge(transportNetwork, both, features, cursor, offsetBuilder,
                            distance, edgeIdx);
                        edgeIdx++;
                        makeTurnEdge(transportNetwork, both, features, cursor, offsetBuilder,
                            distance, edgeIdx);

                        return true;
                    } catch (Exception e) {
                        response.status(500);
                        LOG.error("Exception:", e);
                        return false;
                    }
                });
            }

            featureCollection.put("features", features);

            return featureCollection;
        }, JsonUtilities.objectMapper::writeValueAsString);


        get("debug/streetEdges", (request, response) -> {
            response.header("Content-Type", "application/json");
            if (request.queryParams().size() < 4) {
                response.status(400);
                return "";
            }
            float north = request.queryMap("n").floatValue();
            float south = request.queryMap("s").floatValue();
            float east = request.queryMap("e").floatValue();
            float west = request.queryMap("w").floatValue();
            Boolean iboth = request.queryMap("both").booleanValue();
            Boolean idetail = request.queryMap("detail").booleanValue();
            boolean both, detail;


            if (iboth == null) {
                both = false;
            } else {
                both = iboth;
            }

            if (idetail == null) {
                detail = false;
            } else {
                detail = idetail;
            }

            String layer = "streetEdges"; // request.params(":layer");

            Envelope env = new Envelope(floatingDegreesToFixed(east), floatingDegreesToFixed(west),
                floatingDegreesToFixed(south), floatingDegreesToFixed(north));
            TIntSet streets = transportNetwork.streetLayer.findEdgesInEnvelope(env);

            if (streets.size() > 100_000) {
                LOG.warn("Refusing to include more than 100,000 edges in result");
                response.status(401);
                return "";
            }

            // write geojson to response
            Map<String, Object> featureCollection = new HashMap<>(2);
            featureCollection.put("type", "FeatureCollection");
            List<GeoJsonFeature> features = new ArrayList<>(streets.size());

            EdgeStore.Edge cursor = transportNetwork.streetLayer.edgeStore.getCursor();
            VertexStore.Vertex vcursor = transportNetwork.streetLayer.vertexStore.getCursor();

            BufferParameters bufParams = new BufferParameters();
            bufParams.setSingleSided(true);
            bufParams.setJoinStyle(BufferParameters.JOIN_BEVEL);
            OffsetCurveBuilder offsetBuilder = new OffsetCurveBuilder(new PrecisionModel(),
                bufParams);
            float distance = -0.00005f;

            Set<Integer> seenVertices = new HashSet<>(streets.size());

            if ("streetEdges".equals(layer)) {
                streets.forEach(s -> {
                    try {
                        cursor.seek(s);

                        GeoJsonFeature feature = getEdgeFeature(both, cursor, offsetBuilder,
                            distance, transportNetwork);

                        if (!both) {
                            //Adds fake flag oneway which is added to forward edge if permission flags on forward and backward edge differ.
                            EnumSet<EdgeStore.EdgeFlag> forwardPermissions = cursor.getPermissionFlags();
                            EdgeStore.Edge backwardCursor = transportNetwork.streetLayer.edgeStore.getCursor(s+1);

                            EnumSet<EdgeStore.EdgeFlag> backwardPermissions = backwardCursor.getPermissionFlags();

                            if (!forwardPermissions.equals(backwardPermissions)) {
                                feature.addProperty("ONEWAY", true);
                            }
                        }
                        features.add(feature);

                        getVertexFeatures(cursor, vcursor, seenVertices, features, transportNetwork);
                        if (both) {
                            //backward edge
                            cursor.seek(s+1);
                            feature = getEdgeFeature(both, cursor, offsetBuilder,
                                distance, transportNetwork);
                            features.add(feature);

                        }
                        return true;
                    } catch (Exception e) {
                        response.status(500);
                        LOG.error("Exception:", e);
                        return false;
                    }
                });
            }

            featureCollection.put("features", features);

            return featureCollection;
        }, JsonUtilities.objectMapper::writeValueAsString);

        //Returns flags usage in requested area
        get("debug/stats", (request, response) -> {
            response.header("Content-Type", "application/json");
            Map<String, Object> content = new HashMap<>(2);
            EnumMap<EdgeStore.EdgeFlag, Integer> flagUsage = new EnumMap<>(
                EdgeStore.EdgeFlag.class);
            if (request.queryParams().size() < 4) {

                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor();
                for (int e = 0; e < transportNetwork.streetLayer.edgeStore.nEdges(); e += 2) {
                    edge.seek(e);

                    try {

                        for (EdgeStore.EdgeFlag flag : EdgeStore.EdgeFlag.values()) {
                            if (edge.getFlag(flag)) {
                                Integer currentValue = flagUsage.getOrDefault(flag, 0);
                                flagUsage.put(flag, currentValue + 1);
                            }
                        }
                    } catch (Exception ex) {
                        response.status(500);
                        content.put("errors", "Problem reading edges:" + ex.getMessage());
                        LOG.error("Exception:", e);
                        return content;
                    }
                }
                content.put("data", flagUsage);
                return content;
            }
            float north = request.queryMap("n").floatValue();
            float south = request.queryMap("s").floatValue();
            float east = request.queryMap("e").floatValue();
            float west = request.queryMap("w").floatValue();
            boolean detail = request.queryMap("detail").booleanValue();

            Envelope env = new Envelope(floatingDegreesToFixed(east), floatingDegreesToFixed(west),
                floatingDegreesToFixed(south), floatingDegreesToFixed(north));
            TIntSet streets = transportNetwork.streetLayer.findEdgesInEnvelope(env);
            //transportNetwork.streetLayer.edgeStore.getCursor()

            if (streets.size() > 100_000) {
                LOG.warn("Refusing to include more than 100,000 edges in result");
                response.status(401);
                content.put("errors", "Refusing to include more than 100,000 edges in result");
                return content;
            }

            EdgeStore.Edge cursor = transportNetwork.streetLayer.edgeStore.getCursor();
            VertexStore.Vertex vcursor = transportNetwork.streetLayer.vertexStore.getCursor();
            streets.forEach(s -> {
                try {
                    cursor.seek(s);
                    for (EdgeStore.EdgeFlag flag : EdgeStore.EdgeFlag.values()) {
                        if (cursor.getFlag(flag)) {
                            Integer currentValue = flagUsage.getOrDefault(flag, 0);
                            flagUsage.put(flag, currentValue + 1);
                        }
                    }
                    return true;
                } catch (Exception e) {
                    response.status(500);
                    content.put("errors", "Problem reading edges:" + e.getMessage());
                    LOG.error("Exception:", e);
                    return false;
                }
            });
            content.put("data", flagUsage);
            return content;
        }, JsonUtilities.objectMapper::writeValueAsString);

        //Returns flags usage in requested area
        get("debug/speeds", (request, response) -> {
            response.header("Content-Type", "application/json");
            Map<String, Object> content = new HashMap<>(2);
            Map<Short, Integer> speedUsage = new HashMap<>();
            MinMax minMax = new MinMax();
            if (request.queryParams().size() < 4) {

                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor();
                for (int e = 0; e < transportNetwork.streetLayer.edgeStore.nEdges(); e += 2) {
                    edge.seek(e);

                    try {
                        updateSpeed(edge, speedUsage, minMax);
                    } catch (Exception ex) {
                        response.status(500);
                        content.put("errors", "Problem reading edges:" + ex.getMessage());
                        LOG.error("Exception:", e);
                        return content;
                    }
                }
                content.put("data", speedUsage);
                content.put("min", minMax.min);
                content.put("max", minMax.max);
                return content;
            }
            float north = request.queryMap("n").floatValue();
            float south = request.queryMap("s").floatValue();
            float east = request.queryMap("e").floatValue();
            float west = request.queryMap("w").floatValue();

            Envelope env = new Envelope(floatingDegreesToFixed(east), floatingDegreesToFixed(west),
                floatingDegreesToFixed(south), floatingDegreesToFixed(north));
            TIntSet streets = transportNetwork.streetLayer.findEdgesInEnvelope(env);

            if (streets.size() > 100_000) {
                LOG.warn("Refusing to include more than 100,000 edges in result");
                response.status(401);
                content.put("errors", "Refusing to include more than 100,000 edges in result");
                return content;
            }

            EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor();
            streets.forEach(s -> {
                try {
                    edge.seek(s);
                    updateSpeed(edge, speedUsage, minMax);
                    return true;
                } catch (Exception e) {
                    response.status(500);
                    content.put("errors", "Problem reading edges:" + e.getMessage());
                    LOG.error("Exception:", e);
                    return false;
                }
            });
            content.put("data", speedUsage);
            content.put("min", minMax.min);
            content.put("max", minMax.max);
            return content;
        }, JsonUtilities.objectMapper::writeValueAsString);


        //queries edge store for edgeID and returns envelope of this edge ID
        get("/query", (request, response) -> {
            response.header("Content-Type", "application/json");
            Map<String, Object> content = new HashMap<>(2);
            try {

            Integer edgeID = request.queryMap("edgeID").integerValue();

            //If true returns all the OSM tags of this edge
            Boolean wantTags = request.queryMap("tags").booleanValue();

            if (edgeID == null) {
                content.put("errors", "edgeID is empty!");
            } else {
                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor();
                try {
                    edge.seek(edgeID);
                    content.put("data", edge.getGeometry().getEnvelopeInternal());

                    if (wantTags != null && wantTags) {
                        String osmTags = transportNetwork.streetLayer.getWayTags(edge);
                        if (osmTags != null) {
                            content.put("tags", osmTags);
                        }
                    }
                } catch (Exception ex) {
                    content.put("errors", ex.getMessage());
                    LOG.error("Error getting edge:{}", ex);
                }

            }
            } catch (Exception ex) {
                content.put("errors", "Problem converting edgeID to integer:"+ ex.getMessage());
                LOG.error("Error converting edgeID to integer:{}", ex.getMessage());
            }

            return content;

        }, JsonUtilities.objectMapper::writeValueAsString);

        post("/pareto", paretoServer::handle);
    }

    /**
     * Add a feature to the supplied List of GeoJSON features. Used in street layer debug visualizations.
     */
    private static void makeTurnEdge(TransportNetwork transportNetwork, boolean both,
        List<GeoJsonFeature> features, EdgeStore.Edge cursor, OffsetCurveBuilder offsetBuilder,
        float distance, int edgeIdx) {
        if (transportNetwork.streetLayer.edgeStore.turnRestrictions.containsKey(edgeIdx)) {

            final int numberOfRestrictions =
                    transportNetwork.streetLayer.edgeStore.turnRestrictions.get(edgeIdx).size();
            List<Integer> edge_restricion_idxs = new ArrayList<>(numberOfRestrictions);
            transportNetwork.streetLayer.edgeStore.turnRestrictions.get(edgeIdx)
                .forEach(turn_restriction_idx -> {
                    edge_restricion_idxs.add(turn_restriction_idx);
                    return true;
                });
            for (int i=0; i < edge_restricion_idxs.size(); i++) {
                int turnRestrictionIdx = edge_restricion_idxs.get(i);
                TurnRestriction turnRestriction = transportNetwork.streetLayer.turnRestrictions.get(turnRestrictionIdx);

                //TurnRestriction.fromEdge isn't necessary correct
                //If edge on which from is is splitted then fromEdge is different but isn't updated in TurnRestriction
                cursor.seek(edgeIdx);

                GeoJsonFeature feature = getEdgeFeature(both, cursor, offsetBuilder,
                    distance, transportNetwork);

                feature.addProperty("only", turnRestriction.only);
                feature.addProperty("edge", "FROM");
                feature.addProperty("restrictionId", turnRestrictionIdx);

                features.add(feature);

                if (turnRestriction.viaEdges.length > 0) {
                    for (int idx = 0; idx < turnRestriction.viaEdges.length; idx++) {
                        int via_edge_index = turnRestriction.viaEdges[idx];
                        cursor.seek(via_edge_index);

                        feature = getEdgeFeature(both, cursor, offsetBuilder,
                            distance, transportNetwork);

                        feature.addProperty("only", turnRestriction.only);
                        feature.addProperty("edge", "VIA");
                        feature.addProperty("via_edge_idx", idx);
                        feature.addProperty("restrictionId", turnRestrictionIdx);

                        features.add(feature);
                    }

                }
                cursor.seek(turnRestriction.toEdge);

                feature = getEdgeFeature(both, cursor, offsetBuilder, distance,
                    transportNetwork);

                feature.addProperty("only", turnRestriction.only);
                feature.addProperty("edge", "TO");
                feature.addProperty("restrictionId", turnRestrictionIdx);

                features.add(feature);
            }

        }
    }

    /**
     * Creates features from from and to vertices of provided edge
     * if they weren't already created and they have TRAFFIC_SIGNAL flag
     */
    private static void getVertexFeatures(EdgeStore.Edge cursor, VertexStore.Vertex vcursor,
        Set<Integer> seenVertices, List<GeoJsonFeature> features, TransportNetwork network) {

        int fromVertex = cursor.getFromVertex();
        if (!seenVertices.contains(fromVertex)) {
            vcursor.seek(fromVertex);
            GeoJsonFeature feature = getVertexFeature(vcursor, network);
            //It can be null since we only insert vertices with flags
            if (feature != null) {
                features.add(feature);
            }
            seenVertices.add(fromVertex);
        }
        int toVertex = cursor.getToVertex();
        if (!seenVertices.contains(toVertex)) {
            vcursor.seek(toVertex);
            GeoJsonFeature feature = getVertexFeature(vcursor, network);
            //It can be null since we only insert vertices with flags
            if (feature != null) {
                features.add(feature);
            }
            seenVertices.add(toVertex);
        }
    }

    /**
     * Creates geojson feature from specified vertex
     *
     * Currently it only does that if vertex have TRAFFIC_SIGNAL or BIKE_SHARING flag.
     * Properties in GeoJSON are:
     * - vertex_id
     * - flags: TRAFFIC_SIGNAL or BIKE_SHARING currently not both
     * @param vertex
     * @return
     */
    private static GeoJsonFeature getVertexFeature(VertexStore.Vertex vertex, TransportNetwork network) {
        GeoJsonFeature feature = null;
        if (network.transitLayer.stopForStreetVertex.containsKey(vertex.index)) {
            // jitter transit stops slightly, in a deterministic way, so we can see if they're linked correctly
            feature = new GeoJsonFeature(GeometryUtils.geometryFactory.createPoint(jitter(vertex)));
            //Used for showing stop vertices in debug client
            feature.addProperty("STOP", true);
        } else {
            feature = new GeoJsonFeature(vertex.getLon(), vertex.getLat());
        }

        feature.addProperty("vertex_id", vertex.index);
        //Needed for filtering flags
        for (VertexStore.VertexFlag flag: VertexStore.VertexFlag.values()) {
            if (vertex.getFlag(flag)) {
                feature.addProperty(flag.toString(), true);
            }
        }
        //feature.addProperty("flags", cursor.getFlagsAsString());

        return feature;
    }

    /**
     * Jitter the location of a vertex in a deterministic way.
     * Used to displace transit stops from the vertices they are linked to, so we can see the linking structure of
     * complex stops.
     */
    public static Coordinate jitter (VertexStore.Vertex v) {
        double lat = v.getLat();
        lat += (v.index % 7 - 3.5) * 1e-5;
        double lon = v.getLon();
        lon += (v.index % 11 - 5.5) * 1e-5;
        return new Coordinate(lon, lat);
    }


    private static void fillFeature(TransportNetwork transportNetwork, StreetRouter.State lastState,
        List<GeoJsonFeature> features, boolean reverse) {

        StreetPath streetPath = new StreetPath(lastState, transportNetwork, reverse);

        int stateIdx = 0;

        //TODO: this can be improved since end and start vertices are the same in all the edges.
        for (StreetRouter.State state : streetPath.getStates()) {
            Integer edgeIdx = state.backEdge;
            if (!(edgeIdx == -1 || edgeIdx == null)) {
                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore
                    .getCursor(edgeIdx);
                GeoJsonFeature feature = new GeoJsonFeature(edge.getGeometry());
                feature.addProperty("mode", state.streetMode);
                feature.addProperty("distance", state.distance/1000);
                feature.addProperty("idx", stateIdx++);
                feature.addProperty("stateIdx", state.idx);
                features.add(feature);
                feature.addProperty("edgeIdx", edgeIdx);
            }
        }
    }

    /**
     * Gets feature from edge in EdgeStore as GeoJSON for debug visualization of the street layer.
     * @param both true if we are showing edges in both directions AKA it needs to be offset
     * @param cursor cursor to current forward or reversed edge
     * @param offsetBuilder builder which creates edge offset if needed
     * @param distance for which edge is offset if both is true
     */
    private static GeoJsonFeature getEdgeFeature(boolean both, EdgeStore.Edge cursor,
        OffsetCurveBuilder offsetBuilder, float distance, TransportNetwork network) {
        LineString geometry = cursor.getGeometry();
        Coordinate[] coords = geometry.getCoordinates();

        if (both) {
            coords = offsetBuilder.getOffsetCurve(coords,
                distance);
        }


        if (network.transitLayer.stopForStreetVertex.containsKey(cursor.getFromVertex())) {
            // from vertex is a transit stop, jitter it so that it doesn't sit exactly on top of the street vertex
            // and so that we can see when multiple stops get linked to the same place
            VertexStore.Vertex v = network.streetLayer.vertexStore.getCursor(cursor.getFromVertex());
            coords[0] = jitter(v);
        }

        if (network.transitLayer.stopForStreetVertex.containsKey(cursor.getToVertex())) {
            VertexStore.Vertex v = network.streetLayer.vertexStore.getCursor(cursor.getToVertex());
            coords[coords.length - 1] = jitter(v);
        }
        geometry = GeometryUtils.geometryFactory.createLineString(coords);

        GeoJsonFeature feature = new GeoJsonFeature(geometry);
        feature.addProperty("permission", cursor.getPermissionsAsString());
        feature.addProperty("edge_id", cursor.getEdgeIndex());
        feature.addProperty("speed_ms", cursor.getSpeed());
        feature.addProperty("osmid", cursor.getOSMID());
        //Needed for filtering flags
        for (EdgeStore.EdgeFlag flag: EdgeStore.EdgeFlag.values()) {
            if (cursor.getFlag(flag)) {
                feature.addProperty(flag.toString(), true);
            }
        }
        feature.addProperty("flags", cursor.getFlagsAsString());
        return feature;
    }

    private static void updateSpeed(EdgeStore.Edge edge, Map<Short, Integer> speedUsage,
        MinMax minMax) {
        Short currentEdgeSpeed = edge.getSpeed();
        Integer currentValue = speedUsage.getOrDefault(currentEdgeSpeed, 0);
        speedUsage.put(currentEdgeSpeed, currentValue+1);
        minMax.updateMin(currentEdgeSpeed);
        minMax.updateMax(currentEdgeSpeed);
    }

    private static class MinMax {
        public short min = Short.MAX_VALUE;
        public short max = Short.MIN_VALUE;

        public void updateMin(Short currentEdgeSpeed) {
            min = (short) Math.min(currentEdgeSpeed, min);
        }

        public void updateMax(Short currentEdgeSpeed) {
            max = (short) Math.max(currentEdgeSpeed, max);
        }
    }

    private static float roundSpeed(float speed) {
        return Math.round(speed * 1000) / 1000;
    }

}
