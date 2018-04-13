package com.conveyal.r5;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.csvreader.CsvReader;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

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

        transportNetwork = TransportNetwork.read(new File(NETWORK_DIR, "network.dat"));

        long startTime = System.currentTimeMillis();
        int nRoutesComputed = 0;
        for (CoordPair coordPair : coordPairs) {
            try {
                boolean routingSucceeded = route(coordPair);
                if (routingSucceeded) {
                    nRoutesComputed += 1;
                }
            }
            catch (Exception e) {
                System.out.println("Search failed");
            }
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
    public boolean route (CoordPair coordPair) {
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
        List<String> plan = generateTripPlan(request, bestKnownPath, 0, egressTime);
        plan.stream().forEach(p -> System.out.println(p));
        return true;
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

    private List<String> generateTripPlan(ProfileRequest request, Path path, int accessTime, int egressTime) {
        List<String> legs = new ArrayList<>();
        if (path == null) { return legs; }

        LocalDateTime date = request.date.atStartOfDay();

        for (int i = 0; i < path.patterns.length; i++) {
            String boardStop = transportNetwork.transitLayer.stopNames.get(path.boardStops[i]);
            String alightStop = transportNetwork.transitLayer.stopNames.get(path.alightStops[i]);
            String routeid = transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]).routeId;

            LocalDateTime alightTime = date.plusSeconds(path.alightTimes[i]);

            legs.add("Board stop: " + boardStop + " Alight stop: " + alightStop + " Alight: " + alightTime.toString() + " Pattern: " + routeid);
        }

        legs.add("Arrival time: " + date.plusSeconds(path.alightTimes[path.alightTimes.length-1] + egressTime));

        return legs;
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
