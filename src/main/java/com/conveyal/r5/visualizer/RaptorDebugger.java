package com.conveyal.r5.visualizer;

import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineString;
import gnu.trove.map.TIntIntMap;
import spark.Request;
import spark.Response;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static spark.Spark.get;
import static spark.Spark.port;
import static spark.Spark.post;

/**
 * Created by matthewc on 6/8/16.
 */
public class RaptorDebugger {
    private static TransportNetwork network;

    // TODO memory leak
    private static Map<String, TransportNetwork> scenarioCache = new HashMap<>();

    // allow simultaneous requests using session cookies
    private static Map<String, InstrumentedRaptorWorker> workersForSession = new HashMap<>();

    public static void main (String... args) throws Exception {
        // load transport network
        File network = args.length > 0 ? new File(args[0]) : new File("network.dat");

        if (network.isDirectory()) network = new File(network, "network.dat");

        InputStream inputStream = new BufferedInputStream(new FileInputStream(network));
        RaptorDebugger.network = TransportNetwork.read(inputStream);

        port(4141);
        post("/api/plan", RaptorDebugger::plan, JsonUtilities.objectMapper::writeValueAsString);
        post("/api/continueToNextRound", RaptorDebugger::continueToNextRound, JsonUtilities.objectMapper::writeValueAsString);
        post("/api/continueToNextMinute", RaptorDebugger::continueToNextMinute, JsonUtilities.objectMapper::writeValueAsString);
        post("/api/continueToNextSearch", RaptorDebugger::continueToNextSearch, JsonUtilities.objectMapper::writeValueAsString);

        post("/api/network", RaptorDebugger::getNetwork, JsonUtilities.objectMapper::writeValueAsString);
    }

    /** Start a new request */
    public static RaptorWorkerState plan (Request req, Response res) throws IOException {
        ProfileRequest request = JsonUtilities.objectMapper.readValue(req.body(), ProfileRequest.class);

        TransportNetwork network = request.scenario.modifications.isEmpty() ?
                RaptorDebugger.network : scenarioCache.get(request.scenario.id);

        InstrumentedRaptorWorker worker = new InstrumentedRaptorWorker(network.transitLayer, null, request);

        // Get travel times to street vertices near the origin, and to initial stops if we're using transit.
        long initialStopStartTime = System.currentTimeMillis();
        StreetRouter streetRouter = new StreetRouter(network.streetLayer);

        // default to heaviest mode
        // FIXME what does WALK,CAR even mean in this context
        EnumSet<LegMode> modes = request.accessModes;
        if (modes.contains(LegMode.CAR)) {
            streetRouter.streetMode = StreetMode.CAR;
            streetRouter.distanceLimitMeters = 100_000; // FIXME arbitrary
        } else if (modes.contains(LegMode.BICYCLE)) {
            streetRouter.streetMode = StreetMode.BICYCLE;
            streetRouter.distanceLimitMeters = (int) (request.maxBikeTime * request.bikeSpeed * 60);
        } else {
            streetRouter.streetMode = StreetMode.WALK;
            // When walking, in order to make the search symmetric at origins/destinations,
            // we clamp max walk to the maximum distance recorded in the distance tables.
            streetRouter.distanceLimitMeters =
                    Math.min((int) (request.maxWalkTime * request.walkSpeed * 60), TransitLayer.DISTANCE_TABLE_SIZE_METERS);
        }

        streetRouter.profileRequest = request;
        streetRouter.setOrigin(request.fromLat, request.fromLon);

        // TODO for bike, car access we really want to use weight to account for turn costs, but that's a resource limiting
        // problem.
        streetRouter.dominanceVariable = StreetRouter.State.RoutingVariable.DURATION_SECONDS;

        streetRouter.route();
        TIntIntMap transitStopAccessTimes = streetRouter.getReachedStops();

        worker.runRaptorAsync(transitStopAccessTimes, null, new TaskStatistics());

        String workerId = UUID.randomUUID().toString();

        // clean up repeated requests
        String oldWorkerId = req.session().attribute("workerId");
        if (oldWorkerId != null) workersForSession.remove(oldWorkerId);

        req.session().attribute("workerId", workerId);
        workersForSession.put(workerId, worker);

        return worker.workerState;
    }

    public static RaptorWorkerState continueToNextRound (Request req, Response res) {
        InstrumentedRaptorWorker worker = workersForSession.get(req.session().attribute("workerId"));
        worker.pauseAfterRound = true;
        worker.pauseAfterFrequencySearch = worker.pauseAfterScheduledSearch = worker.pauseAfterDepartureMinute = false;
        return worker.unpause();
    }

    public static RaptorWorkerState continueToNextSearch (Request req, Response res) {
        InstrumentedRaptorWorker worker = workersForSession.get(req.session().attribute("workerId"));
        worker.pauseAfterScheduledSearch = worker.pauseAfterFrequencySearch = true;
        worker.pauseAfterRound = worker.pauseAfterDepartureMinute = false;
        return worker.unpause();
    }

    public static RaptorWorkerState continueToNextMinute (Request req, Response res) {
        InstrumentedRaptorWorker worker = workersForSession.get(req.session().attribute("workerId"));
        worker.pauseAfterDepartureMinute = true;
        worker.pauseAfterFrequencySearch = worker.pauseAfterScheduledSearch = worker.pauseAfterRound = false;
        return worker.unpause();
    }

    public static RaptorDebuggerNetwork getNetwork (Request req, Response res) throws IOException {
        TransportNetwork temp = RaptorDebugger.network;

        Scenario scenario = JsonUtilities.objectMapper.readValue(req.body(), Scenario.class);

        if (!scenario.modifications.isEmpty()) {
            temp = scenario.applyToTransportNetwork(network);
            scenarioCache.put(scenario.id, temp);
        }

        // dodge effectively final nonsense
        final TransportNetwork network = temp;

        RaptorDebuggerNetwork net = new RaptorDebuggerNetwork();
        net.patterns = network.transitLayer.tripPatterns.stream()
                .map(tp -> {
                    Coordinate[] coords = IntStream.of(tp.stops)
                            .map(stop -> network.transitLayer.streetVertexForStop.get(stop))
                            // skip unlinked stops
                            .filter(v -> v >= 0)
                            .mapToObj(v -> {
                                VertexStore.Vertex cursor = network.streetLayer.vertexStore.getCursor(v);
                                return new Coordinate(cursor.getLon(), cursor.getLat());
                            })
                            .toArray(i -> new Coordinate[i]);

                    return GeometryUtils.geometryFactory.createLineString(coords);
                })
                .toArray(i -> new LineString[i]);

        net.stops = IntStream.of(network.transitLayer.streetVertexForStop.toArray())
                .mapToObj(v -> {
                    if (v < 0) return null;
                    VertexStore.Vertex cursor = network.streetLayer.vertexStore.getCursor(v);
                    return new double[] { cursor.getLon(), cursor.getLat() };
                })
                .toArray(i -> new double[i][]);

        double[] lats = Stream.of(net.stops)
                .mapToDouble(s -> s[1])
                .toArray();
        net.lat = lats[lats.length / 2];

        double[] lons = Stream.of(net.stops)
                .mapToDouble(s -> s[0])
                .toArray();
        net.lon = lons[lons.length / 2];

        return net;
    }

    private static class RaptorDebuggerNetwork {
        public double lat, lon;

        public LineString[] patterns;

        // stop id -> double[] { lon, lat }
        public double[][] stops;
    }
}
