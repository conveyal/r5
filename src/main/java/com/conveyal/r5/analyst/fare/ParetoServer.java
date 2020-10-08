package com.conveyal.r5.analyst.fare;

import com.conveyal.analysis.BackendVersion;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.*;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.TIntIntMap;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.IntFunction;

import static spark.Spark.halt;

/**
 * Server for debug tool for fare analysis - to draw pareto surface and show combinations of travel time and fare.
 */
public class ParetoServer {
    public TransportNetwork transportNetwork;

    private static final Logger LOG = LoggerFactory.getLogger(ParetoServer.class);

    public ParetoServer (TransportNetwork transportNetwork) {
        this.transportNetwork = transportNetwork;
    }

    public String handle (Request req, Response res) throws IOException {
        ProfileRequest profileRequest = JsonUtilities.objectMapper.readValue(req.body(), ProfileRequest.class);

        if (profileRequest.inRoutingFareCalculator != null) profileRequest.inRoutingFareCalculator.transitLayer = this.transportNetwork.transitLayer;

        // now perform routing - always using McRaptor
        LOG.info("Performing walk search for access (other access modes not supported)");
        Map<LegMode, TIntIntMap> accessTimes = accessEgressSearch(profileRequest.fromLat, profileRequest.fromLon, profileRequest);
        LOG.info("Performing walk search for egress (other access modes not supported)");
        Map<LegMode, TIntIntMap> egressTimes = accessEgressSearch(profileRequest.toLat, profileRequest.toLon, profileRequest);

        LOG.info("Performing multiobjective transit routing");
        long startTime = System.currentTimeMillis();
        profileRequest.maxTripDurationMinutes = 120; // hack
        IntFunction<DominatingList> listSupplier =
                (departureTime) -> new FareDominatingList(
                        profileRequest.inRoutingFareCalculator,
                        profileRequest.maxFare,
                        // while I appreciate the use of symbolic constants, I certainly hope the number of seconds per
                        // minute does not change
                        // in fact, we have been moving in the opposite direction with leap-second smearing
                        departureTime + profileRequest.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE);
        McRaptorSuboptimalPathProfileRouter mcraptor = new McRaptorSuboptimalPathProfileRouter(
                transportNetwork,
                profileRequest,
                accessTimes,
                egressTimes,
                listSupplier,
                null,
                true); // no collator - route will return states at destination

        mcraptor.route();
        long totalTime = System.currentTimeMillis() - startTime;

        List<ParetoTrip> trips = new ArrayList<>();

        for (TIntObjectIterator<Collection<McRaptorSuboptimalPathProfileRouter.McRaptorState>> it =
             mcraptor.finalStatesByDepartureTime.iterator(); it.hasNext();) {
            it.advance();

            int departureTime = it.key();

            for (McRaptorSuboptimalPathProfileRouter.McRaptorState state : it.value()) {
                trips.add(new ParetoTrip(state, departureTime, transportNetwork));
            }
        }

        ParetoReturn ret = new ParetoReturn(profileRequest, trips, totalTime);

        res.header("Content-Type", "application/json");

        return JsonUtilities.objectMapper.writeValueAsString(ret);
    }

    private Map<LegMode, TIntIntMap> accessEgressSearch (double fromLat, double fromLon, ProfileRequest profileRequest) {
        LOG.info("Performing walk search for access (other access modes not supported)");
        StreetRouter sr = new StreetRouter(transportNetwork.streetLayer);
        sr.profileRequest = profileRequest;
        sr.timeLimitSeconds = 20 * 60; // hardwired at 20 mins
        sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;

        if (!sr.setOrigin(fromLat, fromLon)) {
            halt(404, "Origin or destination not found");
        }

        sr.route();

        TIntIntMap accessTimes = sr.getReachedStops(); // map from stop ID to access time

        if (accessTimes.size() == 0) halt(404, "No transit near origin!");

        Map<LegMode, TIntIntMap> ret = new HashMap<>();
        ret.put(LegMode.WALK, accessTimes);
        return ret;
    }

    /**
     * Many happy returns - class to encapsulate return value.
     */
    public static final class ParetoReturn {
        public final ProfileRequest request;
        public final Collection<ParetoTrip> trips;
        public final long computeTimeMillis;
        /** save backend version in JSON output - useful for JSON that's being pushed to fareto-examples */
        public BackendVersion backendVersion = BackendVersion.instance;
        public String generationTime = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

        public ParetoReturn(ProfileRequest request, Collection<ParetoTrip> trips, long computeTimeMillis) {
            this.request = request;
            this.trips = trips;
            this.computeTimeMillis = computeTimeMillis;
        }
    }

    public static final class ParetoTrip {
        public final int durationSeconds;
        public final int departureTime;
        public final int fare;
        public final List<ParetoLeg> legs;

        public ParetoTrip (McRaptorSuboptimalPathProfileRouter.McRaptorState state, int departureTime, TransportNetwork network) {
            this.departureTime = departureTime;
            this.durationSeconds = state.time - departureTime;
            this.fare = state.fare.cumulativeFarePaid;

            legs = new ArrayList<>();

            while (state != null) {
                if (state.stop != -1 && (state.pattern != -1 || state.back != null)) {
                    if (state.pattern == -1) {
                        int destStopIndex = state.stop;
                        int originStopIndex = state.back.stop;

                        Coordinate originStopCoord = network.transitLayer.getCoordinateForStopFixed(originStopIndex);
                        Coordinate destStopCoord = network.transitLayer.getCoordinateForStopFixed(destStopIndex);

                        int originTime = state.back.time;
                        int destTime = state.time;

                        LineString geom = GeometryUtils.geometryFactory.createLineString(new Coordinate[] {
                                new Coordinate(originStopCoord.getX() / VertexStore.FIXED_FACTOR, originStopCoord.getY() / VertexStore.FIXED_FACTOR),
                                new Coordinate(destStopCoord.getX() / VertexStore.FIXED_FACTOR, destStopCoord.getY() / VertexStore.FIXED_FACTOR),
                        });

                        legs.add(new ParetoTransferLeg(
                                originStopCoord.getY() / VertexStore.FIXED_FACTOR,
                                originStopCoord.getX() / VertexStore.FIXED_FACTOR,
                                destStopCoord.getY() / VertexStore.FIXED_FACTOR,
                                destStopCoord.getX() / VertexStore.FIXED_FACTOR,
                                geom,
                                originTime,
                                destTime,
                                network.transitLayer.stopIdForIndex.get(originStopIndex),
                                network.transitLayer.stopNames.get(originStopIndex),
                                network.transitLayer.stopIdForIndex.get(destStopIndex),
                                network.transitLayer.stopNames.get(destStopIndex),
                                state.fare.cumulativeFarePaid,
                                state.fare.transferAllowance
                        ));

                    } else {
                        TripPattern pattern = network.transitLayer.tripPatterns.get(state.pattern);

                        int boardStopIndex = pattern.stops[state.boardStopPosition];
                        int alightStopIndex = pattern.stops[state.alightStopPosition];

                        Coordinate boardStopCoord = network.transitLayer.getCoordinateForStopFixed(boardStopIndex);
                        Coordinate alightStopCoord = network.transitLayer.getCoordinateForStopFixed(alightStopIndex);

                        List<Coordinate> coords = new ArrayList<>();
                        List<LineString> hops = pattern.getHopGeometries(network.transitLayer);
                        for (int i = state.boardStopPosition; i < state.alightStopPosition; i++) { // hop i is from stop i to i + 1, don't include last stop index
                            LineString hop = hops.get(i);
                            for (Coordinate c : hop.getCoordinates())
                                coords.add(c);
                        }
                        LineString shape = GeometryUtils.geometryFactory.createLineString(coords.toArray(new Coordinate[coords.size()]));

                        legs.add(new ParetoTransitLeg(network.transitLayer.routes.get(pattern.routeIndex),
                                network.transitLayer.stopIdForIndex.get(boardStopIndex), network.transitLayer.stopNames.get(boardStopIndex),
                                network.transitLayer.stopIdForIndex.get(alightStopIndex), network.transitLayer.stopNames.get(alightStopIndex),
                                state.boardTime, state.time, state.fare.cumulativeFarePaid,
                                boardStopCoord.getY() / VertexStore.FIXED_FACTOR,
                                boardStopCoord.getX() / VertexStore.FIXED_FACTOR,
                                alightStopCoord.getY() / VertexStore.FIXED_FACTOR,
                                alightStopCoord.getX() / VertexStore.FIXED_FACTOR, shape, state.fare.transferAllowance));
                    }


                }
                state = state.back;
            }

            Collections.reverse(legs);
        }

    }

    public static final class ParetoTransitLeg extends ParetoLeg {
        public final RouteInfo route;

        public ParetoTransitLeg(RouteInfo route, String boardStopId, String boardStopName, String alightStopId, String alightStopName,
                         int boardTime, int alightTime, int cumulativeFare, double boardStopLat, double boardStopLon,
                         double alightStopLat, double alightStopLon, LineString geom, TransferAllowance transferAllowance) {
            super(boardStopLat, boardStopLon, alightStopLat, alightStopLon, geom, boardTime, alightTime,
                    boardStopId, boardStopName, alightStopId, alightStopName, cumulativeFare, transferAllowance);
            this.route = route;
        }

        @Override public String getType() {
            return "transit";
        }
    }

    public static final class ParetoTransferLeg extends ParetoLeg {
        public ParetoTransferLeg(double originLat, double originLon, double destLat,
                double destLon, LineString geom, int originTime, int destTime,
                String originStopId, String originStopName, String destStopId, String destStopName,
                int cumulativeFare, TransferAllowance transferAllowance) {
            super(originLat, originLon, destLat, destLon, geom, originTime, destTime,
                    originStopId, originStopName, destStopId, destStopName, cumulativeFare,
                    transferAllowance);
        }

        @Override public String getType() {
            return "transfer";
        }
    }

    public static abstract class ParetoLeg {
        public final double originLat;
        public final double originLon;
        public final double destLat;
        public final double destLon;
        public final LineString geom;
        public final int originTime;
        public final int destTime;
        public final String originStopId;
        public final String originStopName;
        public final String destStopId;
        public final String destStopName;
        public final int cumulativeFare;
        public final TransferAllowance transferAllowance;

        protected ParetoLeg(double originLat, double originLon, double destLat, double destLon,
                LineString geom, int originTime, int destTime, String originStopId,
                String originStopName, String destStopId, String destStopName, int cumulativeFare,
                TransferAllowance transferAllowance) {
            this.originLat = originLat;
            this.originLon = originLon;
            this.destLat = destLat;
            this.destLon = destLon;
            this.geom = geom;
            this.originTime = originTime;
            this.destTime = destTime;
            this.originStopId = originStopId;
            this.originStopName = originStopName;
            this.destStopId = destStopId;
            this.destStopName = destStopName;
            this.cumulativeFare = cumulativeFare;
            this.transferAllowance = transferAllowance;
        }

        public abstract String getType();
    }
}
