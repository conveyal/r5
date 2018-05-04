package com.conveyal.r5.speed_test;

import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.speed_test.api.model.AgencyAndId;
import com.conveyal.r5.speed_test.api.model.EncodedPolylineBean;
import com.conveyal.r5.speed_test.api.model.Itinerary;
import com.conveyal.r5.speed_test.api.model.Leg;
import com.conveyal.r5.speed_test.api.model.Place;
import com.conveyal.r5.speed_test.api.model.PolylineEncoder;
import com.conveyal.r5.speed_test.api.model.TripPlan;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.csvreader.CsvReader;
import com.vividsolutions.jts.geom.Coordinate;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;

/**
 * Test response times for a large batch of origin/destination points.
 * Also demonstrates how to run basic searches without using the graphQL profile routing API.
 */
public class SpeedTest {

    private static final String COORD_PAIRS = "travelSearch.csv";
    private static final String NETWORK_DATA_FILE = "network.dat";

    private static TransportNetwork transportNetwork;

    private final File rootDir;

    SpeedTest(String[] args) throws Exception {
        this(rootDirArgument(args));
    }

    private SpeedTest(File rootDir) throws Exception {
        this.rootDir = rootDir;
        initTransportNetwork();
    }

    private void initTransportNetwork() throws Exception {
        synchronized (NETWORK_DATA_FILE) {
            if (transportNetwork == null) {
                transportNetwork = TransportNetwork.read(new File(rootDir, NETWORK_DATA_FILE));
                transportNetwork.rebuildTransientIndexes();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new SpeedTest(args).run();
    }

    public void run() throws Exception {
        List<CoordPair> coordPairs = getCoordPairs();
        List<TripPlan> tripPlans = new ArrayList<>();

        long startTime = System.currentTimeMillis();
        int nRoutesComputed = 0;
        for (CoordPair coordPair : coordPairs) {
            try {
                ProfileRequest request = buildDefaultRequest(coordPair);
                tripPlans.add(route(request, 1));
                nRoutesComputed++;
            } catch (Exception e) {
                System.out.println("Search failed");
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;

        System.out.println("Average path search time (msec): " + elapsedTime / nRoutesComputed);
        System.out.println("Successful searches: " + nRoutesComputed + " / " + coordPairs.size());
        System.out.println("Total time: " + elapsedTime / 1000 + " seconds");
    }

    public TripPlan route(ProfileRequest request, int numberOfItineraries) {
        TripPlan tripPlan = new TripPlan();
        tripPlan.date = new Date(request.date.atStartOfDay(ZoneId.of("Europe/Oslo")).toInstant().toEpochMilli() + request.fromTime * 1000);
        tripPlan.from = new Place(request.fromLon, request.fromLat, "Origin");
        tripPlan.to = new Place(request.toLon, request.toLat, "Destination");

        for (int i = 0; i < numberOfItineraries; i++) {
            StreetRouter egressRouter = streetRoute(request, true);
            StreetRouter accessRouter = streetRoute(request, false);

            TIntIntMap egressTimesToStopsInSeconds = egressRouter.getReachedStops();
            TIntIntMap accessTimesToStopsInSeconds = accessRouter.getReachedStops();

            FastRaptorWorker worker = new FastRaptorWorker(transportNetwork.transitLayer, request, accessTimesToStopsInSeconds);
            worker.retainPaths = true;

            // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
            // Returns the total travel times as a 2D array of [searchIteration][destinationStopIndex].
            // Additional detailed path information is retained in the FastRaptorWorker after routing.
            int[][] transitTravelTimesToStops = worker.route();

            int bestKnownTime = Integer.MAX_VALUE; // Hack to bypass Java stupid "effectively final" requirement.
            Path bestKnownPath = null;
            TIntIntIterator egressTimeIterator = egressTimesToStopsInSeconds.iterator();
            int egressTime = 0;
            int accessTime = 0;
            while (egressTimeIterator.hasNext()) {
                egressTimeIterator.advance();
                int stopIndex = egressTimeIterator.key();
                egressTime = egressTimeIterator.value();
                int travelTimeToStop = transitTravelTimesToStops[0][stopIndex];
                if (travelTimeToStop != FastRaptorWorker.UNREACHED) {
                    int totalTime = travelTimeToStop + egressTime;
                    if (totalTime < bestKnownTime) {
                        bestKnownTime = totalTime;
                        bestKnownPath = worker.pathsPerIteration.get(0)[stopIndex];
                        accessTime = accessTimesToStopsInSeconds.get(worker.pathsPerIteration.get(0)[stopIndex].boardStops[0]);
                    }
                }
            }

            StreetRouter.State accessState = accessRouter.getStateAtVertex(transportNetwork.transitLayer.streetVertexForStop
                    .get(bestKnownPath.boardStops[0]));
            StreetRouter.State egressState = egressRouter.getStateAtVertex(transportNetwork.transitLayer.streetVertexForStop
                    .get(bestKnownPath.alightStops[bestKnownPath.alightStops.length - 1]));

            StreetPath accessPath = new StreetPath(accessState, transportNetwork, false);
            StreetPath egressPath = new StreetPath(egressState, transportNetwork, false);

            System.out.println("Best path: " + (bestKnownPath == null ? "NONE" : bestKnownPath.toString()));
            Itinerary itinerary = generateItinerary(request, bestKnownPath, accessPath, egressPath);
            if (itinerary != null) {
                tripPlan.itinerary.add(itinerary);
                Calendar fromMidnight = (Calendar)itinerary.startTime.clone();
                fromMidnight.set(Calendar.HOUR, 0);
                fromMidnight.set(Calendar.MINUTE, 0);
                fromMidnight.set(Calendar.SECOND, 0);
                fromMidnight.set(Calendar.MILLISECOND, 0);
                request.fromTime = (int)(itinerary.startTime.getTimeInMillis() - fromMidnight.getTimeInMillis()) / 1000 + 60;
                request.toTime = request.fromTime + 60;
            } else {
                break;
            }
        }
        return tripPlan;
    }

    /**
     * @return true if the search succeeded.
     */
    private ProfileRequest buildDefaultRequest(CoordPair coordPair) {
        ProfileRequest request = new ProfileRequest();

        request.accessModes = request.egressModes = request.directModes = EnumSet.of(LegMode.WALK);
        request.maxWalkTime = 20;
        request.maxTripDurationMinutes = 1200;
        request.transitModes = EnumSet.of(TransitModes.TRAM, TransitModes.SUBWAY, TransitModes.RAIL, TransitModes.BUS);
        request.fromLat = coordPair.fromLat;
        request.fromLon = coordPair.fromLon;
        request.toLat = coordPair.toLat;
        request.toLon = coordPair.toLon;
        request.fromTime = 8 * 60 * 60; // 8AM in seconds since midnight
        request.toTime = request.fromTime + 60;
        request.date = LocalDate.of(2018, 04, 13);

        return request;
    }

    private StreetRouter streetRoute(ProfileRequest request, boolean fromDest) {
        // Search for access to / egress from transit on streets.
        StreetRouter sr = new StreetRouter(transportNetwork.streetLayer);
        sr.profileRequest = request;
        if (!fromDest ? !sr.setOrigin(request.fromLat, request.fromLon) : !sr.setOrigin(request.toLat, request.toLon)) {
            throw new RuntimeException("Point not near a road.");
        }
        sr.timeLimitSeconds = request.maxWalkTime * 60;
        sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        sr.route();
        return sr;
    }

    private StreetPath getWalkLegCoordinates(int originStop, int destinationStop) {
        StreetRouter sr = new StreetRouter(transportNetwork.streetLayer);
        sr.profileRequest = new ProfileRequest();
        int originStreetVertex = transportNetwork.transitLayer.streetVertexForStop.get(originStop);
        sr.setOrigin(originStreetVertex);
        sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        sr.distanceLimitMeters = 1000;
        sr.route();
        StreetRouter.State transferState = sr.getStateAtVertex(transportNetwork.transitLayer.streetVertexForStop
                .get(destinationStop));
        StreetPath transferPath = new StreetPath(transferState, transportNetwork, false);
        return transferPath;
    }

    private Itinerary generateItinerary(ProfileRequest request, Path path, StreetPath accessPath, StreetPath egressPath) {
        Itinerary itinerary = new Itinerary();
        if (path == null) {
            return null;
        }

        itinerary.walkDistance = 0.0;
        itinerary.transitTime = 0;
        itinerary.waitingTime = 0;
        itinerary.weight = 0;

        int accessTime = accessPath.getDuration();
        int egressTime = egressPath.getDuration();

        List<Coordinate> acessCoords = accessPath.getEdges().stream()
                .map(t -> new Coordinate(accessPath.getEdge(t).getGeometry().getCoordinate().x, accessPath.getEdge(t).getGeometry().getCoordinate().y)).collect(Collectors.toList());
        List<Coordinate> egressCoords = egressPath.getEdges().stream()
                .map(t -> new Coordinate(egressPath.getEdge(t).getGeometry().getCoordinate().x, egressPath.getEdge(t).getGeometry().getCoordinate().y)).collect(Collectors.toList());
        Collections.reverse(egressCoords);

        // Access leg
        Leg accessLeg = new Leg();

        Stop firstStop = transportNetwork.transitLayer.stopForIndex.get(path.boardStops[0]);

        accessLeg.startTime = getCalendarFromTimeInSeconds(request.date, (path.boardTimes[0] - accessTime));
        accessLeg.endTime = getCalendarFromTimeInSeconds(request.date, path.boardTimes[0]);
        accessLeg.from = new Place(request.fromLon, request.fromLat, "Origin");
        accessLeg.to = new Place(firstStop.stop_lat, firstStop.stop_lon, firstStop.stop_name);
        accessLeg.to.stopId = new AgencyAndId("RB", firstStop.stop_id);
        accessLeg.mode = "WALK";
        accessLeg.legGeometry = PolylineEncoder.createEncodings(acessCoords);

        accessLeg.distance = (double)accessPath.getDistance();

        itinerary.walkDistance += accessLeg.distance;

        itinerary.addLeg(accessLeg);

        for (int i = 0; i < path.patterns.length; i++) {
            Stop boardStop = transportNetwork.transitLayer.stopForIndex.get(path.boardStops[i]);
            Stop alightStop = transportNetwork.transitLayer.stopForIndex.get(path.alightStops[i]);

            // Transfer leg if present
            if (i > 0 && path.transferTimes[i] != -1) {
                Stop previousAlightStop = transportNetwork.transitLayer.stopForIndex.get(path.alightStops[i - 1]);
                StreetPath transferPath = getWalkLegCoordinates(path.alightStops[i - 1], path.boardStops[i]);
                List<Coordinate> transferCoords = transferPath.getEdges().stream()
                        .map(t -> new Coordinate(transferPath.getEdge(t).getGeometry().getCoordinate().x, transferPath
                                .getEdge(t).getGeometry().getCoordinate().y)).collect(Collectors.toList());

                Leg transferLeg = new Leg();
                transferLeg.startTime = getCalendarFromTimeInSeconds(request.date, path.alightTimes[i - 1]);
                transferLeg.endTime = getCalendarFromTimeInSeconds(request.date, path.alightTimes[i - 1] + path.transferTimes[i]);
                transferLeg.mode = "WALK";
                transferLeg.from = new Place(previousAlightStop.stop_lat, previousAlightStop.stop_lon, previousAlightStop.stop_name);
                transferLeg.to = new Place(boardStop.stop_lat, boardStop.stop_lon, boardStop.stop_name);
                transferLeg.legGeometry = PolylineEncoder.createEncodings(transferCoords);

                transferLeg.distance = (double)transferPath.getDistance();

                itinerary.walkDistance += transferLeg.distance;

                itinerary.addLeg(transferLeg);
            }

            // Transit leg
            Leg transitLeg = new Leg();

            transitLeg.distance = 0.0;

            RouteInfo routeInfo = transportNetwork.transitLayer.routes
                    .get(transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]).routeIndex);
            TripSchedule tripSchedule = transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]).tripSchedules.get(path.trips[i]);
            TripPattern tripPattern = transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]);

            itinerary.transitTime += path.alightTimes[i] - path.boardTimes[i];

            itinerary.waitingTime += path.boardTimes[i] - path.transferTimes[i];

            transitLeg.from = new Place(boardStop.stop_lat, boardStop.stop_lon, boardStop.stop_name);
            transitLeg.from.stopId = new AgencyAndId("RB", boardStop.stop_id);
            transitLeg.to = new Place(alightStop.stop_lat, alightStop.stop_lon, alightStop.stop_name);
            transitLeg.to.stopId = new AgencyAndId("RB", alightStop.stop_id);

            transitLeg.route = routeInfo.route_short_name;
            transitLeg.agencyName = routeInfo.agency_name;
            transitLeg.routeColor = routeInfo.color;
            transitLeg.tripShortName = tripSchedule.tripId;
            transitLeg.agencyId = routeInfo.agency_id;
            transitLeg.routeShortName = routeInfo.route_short_name;
            transitLeg.routeLongName = routeInfo.route_long_name;
            transitLeg.mode = TransitLayer.getTransitModes(routeInfo.route_type).toString();

            List<Coordinate> transitLegCoordinates = new ArrayList<>();
            boolean boarded = false;
            for (int j = 0; j < tripPattern.stops.length; j++) {
                if (!boarded && tripSchedule.departures[j] == path.boardTimes[i]) {
                    boarded = true;
                }
                if (boarded) {
                    transitLegCoordinates.add(new Coordinate(transportNetwork.transitLayer.stopForIndex.get(tripPattern.stops[j]).stop_lon,
                            transportNetwork.transitLayer.stopForIndex.get(tripPattern.stops[j]).stop_lat ));
                }
                if (boarded && tripSchedule.arrivals[j] == path.alightTimes[i]) {
                    break;
                }
            }

            transitLeg.legGeometry = PolylineEncoder.createEncodings(transitLegCoordinates);

            transitLeg.startTime = getCalendarFromTimeInSeconds(request.date, path.boardTimes[i]);
            transitLeg.endTime = getCalendarFromTimeInSeconds(request.date, path.alightTimes[i]);
            itinerary.addLeg(transitLeg);
        }

        // Egress leg
        Leg egressLeg = new Leg();
        Stop lastStop = transportNetwork.transitLayer.stopForIndex.get(path.alightStops[path.length - 1]);
        egressLeg.startTime = getCalendarFromTimeInSeconds(request.date, path.alightTimes[path.alightTimes.length - 1]);
        egressLeg.endTime = getCalendarFromTimeInSeconds(request.date, path.alightTimes[path.alightTimes.length - 1] + egressTime);
        egressLeg.from = new Place(lastStop.stop_lat, lastStop.stop_lon, lastStop.stop_name);
        egressLeg.from.stopId = new AgencyAndId("RB", lastStop.stop_id);
        egressLeg.to = new Place(request.toLon, request.toLat, "Destination");
        egressLeg.mode = "WALK";
        egressLeg.legGeometry = PolylineEncoder.createEncodings(egressCoords);

        egressLeg.distance = (double)egressPath.getDistance();

        itinerary.walkDistance += egressLeg.distance;

        itinerary.addLeg(egressLeg);

        itinerary.duration = (long) accessTime + (path.alightTimes[path.length - 1] - path.boardTimes[0]) + egressTime;
        itinerary.startTime = accessLeg.startTime;
        itinerary.endTime = egressLeg.endTime;

        itinerary.transfers = path.patterns.length - 1;

        return itinerary;
    }

    private Calendar getCalendarFromTimeInSeconds(LocalDate date, int seconds) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Oslo"));
        calendar.set(date.getYear(), date.getMonth().getValue(), date.getDayOfMonth()
                , 0, 0, 0);
        calendar.add(Calendar.SECOND, seconds);
        return calendar;
    }

    private List<CoordPair> getCoordPairs() throws IOException {
        List<CoordPair> coordPairs = new ArrayList<>();
        CsvReader csvReader = new CsvReader(new File(rootDir, COORD_PAIRS).getAbsolutePath());
        csvReader.readRecord(); // Skip header
        while (csvReader.readRecord()) {
            CoordPair coordPair = new CoordPair();
            coordPair.fromLat = Double.parseDouble(csvReader.get(2));
            coordPair.fromLon = Double.parseDouble(csvReader.get(3));
            coordPair.toLat = Double.parseDouble(csvReader.get(6));
            coordPair.toLon = Double.parseDouble(csvReader.get(7));
            coordPairs.add(coordPair);
        }
        return coordPairs;
    }

    private static File rootDirArgument(String[] args) {
        if(args.length != 1) {
            return new File(".");
        }
        File rootDir = new File(args[0]);

        if(!rootDir.exists()) {
            throw new IllegalArgumentException("Unable to find SpeedTest root directory: " + args[0]);
        }
        return rootDir;
    }

    private class CoordPair {

        public double fromLat;
        public double fromLon;
        public double toLat;
        public double toLon;
    }
}
