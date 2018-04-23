package com.conveyal.r5.speed_test;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.speed_test.api.model.Itinerary;
import com.conveyal.r5.speed_test.api.model.Leg;
import com.conveyal.r5.speed_test.api.model.Place;
import com.conveyal.r5.speed_test.api.model.TripPlan;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.csvreader.CsvReader;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.TimeZone;

/**
 * Test response times for a large batch of origin/destination points.
 * Also demonstrates how to run basic searches without using the graphQL profile routing API.
 */
public class SpeedTest {

    public static final String NETWORK_DIR = "./src/main/resources/speed_test/";

    public static final String COORD_PAIRS = "./src/main/resources/speed_test/travelSearch.csv";

    private TransportNetwork transportNetwork;

    public static void main (String[] args) throws Exception {
        new SpeedTest().run();
    }

    public void run () throws Exception {
        List<CoordPair> coordPairs = getCoordPairs();
        List<TripPlan> tripPlans = new ArrayList<>();

        transportNetwork = TransportNetwork.read(new File(NETWORK_DIR, "network.dat"));

        long startTime = System.currentTimeMillis();
        int nRoutesComputed = 0;
        for (CoordPair coordPair : coordPairs) {
            //try {
                ProfileRequest request = buildRequest(coordPair);
                tripPlans.add(route(request));
                nRoutesComputed++;
            //}
            //catch (Exception e) {
            //    System.out.println("Search failed");
            //}
        }

        long elapsedTime = System.currentTimeMillis() - startTime;

        System.out.println("Average path search time (msec): " + elapsedTime / nRoutesComputed);
        System.out.println("Successful searches: " + nRoutesComputed + " / " + coordPairs.size());
        System.out.println("Total time: " + elapsedTime / 1000 + " seconds");
    }

    /**
     *
     * @return true if the search succeeded.
     */
    public ProfileRequest buildRequest (CoordPair coordPair) {
        ProfileRequest request = new ProfileRequest();

        request.accessModes =  request.egressModes = request.directModes = EnumSet.of(LegMode.WALK);
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

    public TripPlan route (ProfileRequest request) {
        TIntIntMap accessTimesToStopsInSeconds = streetRoute(request, false);
        TIntIntMap egressTimesToStopsInSeconds = streetRoute(request, true);

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
                }
            }
        }
        System.out.println("Best path: " + (bestKnownPath == null ? "NONE" : bestKnownPath.toString()));
        TripPlan plan = generateTripPlan(request, bestKnownPath, 0, egressTime);
        return plan;
    }

    private TIntIntMap streetRoute (ProfileRequest request, boolean fromDest) {
        // Search for access to / egress from transit on streets.
        StreetRouter sr = new StreetRouter(transportNetwork.streetLayer);
        sr.profileRequest = request;
        if ( !fromDest ? !sr.setOrigin(request.fromLat, request.fromLon) : !sr.setOrigin(request.toLat, request.toLon)) {
            throw new RuntimeException("Point not near a road.");
        }
        sr.timeLimitSeconds = request.maxWalkTime * 60;
        sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        sr.route();
        return sr.getReachedStops();
    }

    private List<String> generateTripPlanString(ProfileRequest request, Path path, int accessTime, int egressTime) {
        List<String> legs = new ArrayList<>();
        if (path == null) { return legs; }

        LocalDateTime date = request.date.atStartOfDay();

        legs.add("Departure time: " + date.plusSeconds(path.boardTimes[0] - accessTime));

        legs.add("Access time: " + Duration.ofSeconds(accessTime));

        for (int i = 0; i < path.patterns.length; i++) {
            String boardStop = transportNetwork.transitLayer.stopNames.get(path.boardStops[i]);
            String alightStop = transportNetwork.transitLayer.stopNames.get(path.alightStops[i]);
            String routeid = transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]).routeId;
            String tripId = transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]).tripSchedules.get(path.trips[i]).tripId;

            LocalDateTime boardTime = date.plusSeconds(path.boardTimes[i]);
            LocalDateTime alightTime = date.plusSeconds(path.alightTimes[i]);
            Duration transferTime = Duration.ofSeconds(path.transferTimes[i]);

            if (transferTime.getSeconds() != -1) {
                legs.add("Transfer time: " + transferTime.toString());
            }

            legs.add(" Board stop: " + boardStop + " Alight stop: " + alightStop + "Board: " + boardTime.toString() + " Alight: " + alightTime.toString() + " Pattern: " + routeid + " Trip: " + tripId);
        }

        legs.add("Egress time: " + Duration.ofSeconds(egressTime));

        legs.add("Arrival time: " + date.plusSeconds(path.alightTimes[path.alightTimes.length-1] + egressTime));

        return legs;
    }

    public TripPlan generateTripPlan(ProfileRequest request, Path path, int accessTime, int egressTime) {
        TripPlan tripPlan = new TripPlan();
        tripPlan.date = java.sql.Timestamp.valueOf(request.date.atStartOfDay());
        tripPlan.from = new Place(request.fromLon, request.fromLat, "");
        tripPlan.to = new Place(request.toLon, request.toLat, "");

        if (path == null) { return tripPlan; }

        Itinerary itinerary = new Itinerary();

        // Access leg
        Leg accessLeg = new Leg();
        accessLeg.startTime = getCalendarFromTimeInSeconds(request.date, (path.boardTimes[0] - accessTime));
        accessLeg.endTime = getCalendarFromTimeInSeconds(request.date, path.boardTimes[0]);

        itinerary.addLeg(accessLeg);

        for (int i = 0; i < path.patterns.length; i++) {
            // Transfer leg if present
            if (i > 0 && path.transferTimes[i] != -1) {
                Leg transferLeg = new Leg();
                transferLeg.startTime = getCalendarFromTimeInSeconds(request.date, path.alightTimes[i - 1]);
                transferLeg.endTime = getCalendarFromTimeInSeconds(request.date, path.alightTimes[i - 1] + path.transferTimes[i]);
                itinerary.addLeg(transferLeg);
            }

            // Transit leg
            Leg transitLeg = new Leg();

            String routeid = transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]).routeId;
            String tripId = transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]).tripSchedules.get(path.trips[i]).tripId;

            transitLeg.route = routeid;
            transitLeg.tripShortName = tripId;
            transitLeg.mode = TransitLayer.getTransitModes(transportNetwork.transitLayer.routes
                    .get(transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]).routeIndex).route_type).toString();

            transitLeg.startTime = getCalendarFromTimeInSeconds(request.date, path.boardTimes[i]);
            transitLeg.endTime = getCalendarFromTimeInSeconds(request.date, path.alightTimes[i]);
            itinerary.addLeg(transitLeg);
        }

        // Egress leg
        Leg egressLeg = new Leg();
        egressLeg.startTime = getCalendarFromTimeInSeconds(request.date, path.alightTimes[path.alightTimes.length-1]);
        egressLeg.endTime = getCalendarFromTimeInSeconds(request.date, path.alightTimes[path.alightTimes.length-1] + egressTime);

        itinerary.addLeg(egressLeg);

        tripPlan.itinerary.add(itinerary);

        return tripPlan;
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
        CsvReader csvReader = new CsvReader(COORD_PAIRS);
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

    public class CoordPair {
        public double fromLat;
        public double fromLon;
        public double toLat;
        public double toLon;
    }
}
