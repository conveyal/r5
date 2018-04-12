package com.conveyal.r5;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;

import java.io.File;
import java.time.LocalDate;
import java.util.EnumSet;

/**
 * Test response times for a large batch of origin/destination points.
 * Also demonstrates how to run basic searches without using the graphQL profile routing API.
 */
public class SpeedTest {

    public static final String NETWORK_DIR = "/Users/abyrd/r5/norway";

    public static final String STOP_PAIRS = "~/norway-stops.csv";

    private TransportNetwork transportNetwork;

    public static void main (String[] args) throws Exception {
        new SpeedTest().run();
    }

    public void run () throws Exception {

        // CsvReader csvReader = new CsvReader(STOP_PAIRS))

        transportNetwork = TransportNetwork.read(new File(NETWORK_DIR, "network.dat"));

        long startTime = System.currentTimeMillis();
        int nRoutesComputed = 0;
        while (true) {
            boolean routingSucceeded = route();
            if (routingSucceeded) {
                nRoutesComputed += 1;
            }
            if (nRoutesComputed % 10 == 0) {
                long elapsedTime = System.currentTimeMillis() - startTime;
                System.out.println("Average path search time (msec): " +  elapsedTime / nRoutesComputed);
            }
        }

    }

    /**
     *
     * @return true if the search succeeded.
     */
    public boolean route () {
        ProfileRequest request = new ProfileRequest();
        request.accessModes =  request.egressModes = request.directModes = EnumSet.of(LegMode.WALK);
        request.maxWalkTime = 20;
        request.transitModes = EnumSet.of(TransitModes.TRAM, TransitModes.SUBWAY, TransitModes.RAIL, TransitModes.BUS);
        // fromLat: 59.90965, fromLon: 10.754923, toLat: 60.390495, toLon: 5.332859,
        // fromTime: "2018-03-21T09:00:00+01:00", toTime: "2018-03-21T09:10:00+01:00",
        // accessModes: [WALK], egressModes: [WALK]
        request.fromLat = 59.90965;
        request.fromLon = 10.754923;
        request.toLat = 60.390495;
        request.toLon = 5.332859;
        request.fromTime = 8 * 60 * 60; // 8AM in seconds since midnight
        request.toTime = request.fromTime + 60;
        request.date = LocalDate.of(2018, 03, 21);

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
        while (egressTimeIterator.hasNext()) {
            egressTimeIterator.advance();
            int stopIndex = egressTimeIterator.key();
            int egressTime = egressTimeIterator.value();
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
        return true;
    }

    private TIntIntMap streetRoute (ProfileRequest request, boolean fromDest) {
        // Search for access to / egress from transit on streets.
        StreetRouter sr = new StreetRouter(transportNetwork.streetLayer);
        sr.profileRequest = request;
        if ( ! sr.setOrigin(request.fromLat, request.fromLon)) {
            throw new RuntimeException("Point not near a road.");
        }
        sr.timeLimitSeconds = request.maxWalkTime * 60;
        sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        sr.route();
        return sr.getReachedStops();
    }

}
