package com.conveyal.r5.speed_test;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.SearchAlgorithm;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.profile.mcrr.MultiCriteriaRangeRaptorWorker;
import com.conveyal.r5.speed_test.api.model.Itinerary;
import com.conveyal.r5.speed_test.api.model.Place;
import com.conveyal.r5.speed_test.api.model.TripPlan;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.AvgTimer;
import gnu.trove.iterator.TIntIntIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;

import static com.conveyal.r5.profile.SearchAlgorithm.MultiCriteriaRangeRaptor;
import static com.conveyal.r5.profile.SearchAlgorithm.RangeRaptor;

/**
 * Test response times for a large batch of origin/destination points.
 * Also demonstrates how to run basic searches without using the graphQL profile routing API.
 */
public class SpeedTest {

    private static final Logger LOG = LoggerFactory.getLogger(SpeedTest.class);


    private static final String COORD_PAIRS = "travelSearch.csv";
    private static final String NETWORK_DATA_FILE = "network.dat";

    private static TransportNetwork transportNetwork;
    private static ItineraryMapper itineraryMapper;

    private CommandLineOpts opts;
    private AvgTimer timer = AvgTimer.timerMilliSec("SpeedTest:route");

    SpeedTest(CommandLineOpts opts) throws Exception {
        this.opts = opts;
        initTransportNetwork();
    }

    private void initTransportNetwork() throws Exception {
        synchronized (NETWORK_DATA_FILE) {
            if (transportNetwork == null) {
                transportNetwork = TransportNetwork.read(new File(opts.rootDir(), NETWORK_DATA_FILE));
                transportNetwork.rebuildTransientIndexes();
                itineraryMapper = new ItineraryMapper(transportNetwork);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        SpeedTestCmdLineOpts opts = new SpeedTestCmdLineOpts(args);
        new SpeedTest(opts).run(opts);
    }

    public void run(SpeedTestCmdLineOpts opts) throws Exception {
        List<CsvTestCase> testCases = CsvTestCase.getCoordPairs(new File(this.opts.rootDir(), COORD_PAIRS));
        List<TripPlan> tripPlans = new ArrayList<>();


        int nRoutesComputed = 0;

        // Warm up JIT compiler
        runSingleTestCase(tripPlans, testCases.get(10), opts);
        runSingleTestCase(tripPlans, testCases.get(15), opts);


        LOG.info("\n- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - [ START ]");

        AvgTimer.resetAll();

        for (CsvTestCase testCase : testCases) {
            boolean ok = runSingleTestCase(tripPlans, testCase, opts);
            nRoutesComputed += ok ? 1 : 0;
        }

        LOG.info(
                "\n- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - [ SUMMARY ]\n" +
                        AvgTimer.listResults().stream().reduce("", (text, line) -> text + line + "\n")
        );

        LOG.info("Successful searches: " + nRoutesComputed + " / " + testCases.size());
        LOG.info("Total time: " + timer.totalTimeInSeconds() + " seconds");
    }

    private boolean runSingleTestCase(List<TripPlan> tripPlans, CsvTestCase testCase, SpeedTestCmdLineOpts opts) {
        try {
            final ProfileRequest request = buildDefaultRequest(testCase, opts);
            timer.start();
            TripPlan route = route(request);
            timer.stop();
            tripPlans.add(route);
            printResult(route.getItineraries().size(), testCase, timer.lapTime(), "");
            return true;
        } catch (ConcurrentModificationException | NullPointerException e) {
            timer.fail();
            printError(testCase, timer.lapTime(), e);
            e.printStackTrace();
        } catch (Exception e) {
            timer.fail();
            printError(testCase, timer.lapTime(), e);
        }
        return false;
    }

    public TripPlan route(ProfileRequest request) {
        SearchAlgorithm algorithm = request.algorithm;
        if (algorithm == null) {
            algorithm = opts.useMultiCriteriaSearch() ? MultiCriteriaRangeRaptor : RangeRaptor;
        }
        switch (algorithm) {
            case RangeRaptor:
                return routeRangeRaptor(request);
            case MultiCriteriaRangeRaptor:
                return routeMcrr(request);
        }
        throw new IllegalArgumentException("Algorithm not supported: " + request.algorithm);
    }


    public TripPlan routeRangeRaptor(ProfileRequest request) {
        TripPlan tripPlan = createTripPlanForRequest(request);

        for (int i = 0; i < request.numberOfItineraries; i++) {
            EgressAccessRouter streetRouter = new EgressAccessRouter(transportNetwork, request);
            streetRouter.route();

            FastRaptorWorker worker = new FastRaptorWorker(transportNetwork.transitLayer, request, streetRouter.accessTimesToStopsInSeconds);
            worker.retainPaths = true;

            // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
            // Returns the total travel times as a 2D array of [searchIteration][destinationStopIndex].
            // Additional detailed path information is retained in the FastRaptorWorker after routing.
            int[][] transitTravelTimesToStops = worker.route();

            int bestKnownTime = Integer.MAX_VALUE; // Hack to bypass Java stupid "effectively final" requirement.
            Path bestKnownPath = null;
            TIntIntIterator egressTimeIterator = streetRouter.egressTimesToStopsInSeconds.iterator();
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
                        accessTime = streetRouter.accessTimesToStopsInSeconds.get(worker.pathsPerIteration.get(0)[stopIndex].boardStops[0]);
                    }
                }
            }

            if (bestKnownPath == null) {
                LOG.info("\n\n!!! NO RESULT FOR INPUT. From ({}, {}) to ({}, {}).\n", request.fromLat, request.fromLon, request.toLat, request.toLon);
                break;
            }

            Itinerary itinerary = createItinerary(request, streetRouter, bestKnownPath);

            if (itinerary != null) {
                tripPlan.addItinerary(itinerary);
                Calendar fromMidnight = (Calendar) itinerary.startTime.clone();
                fromMidnight.set(Calendar.HOUR, 0);
                fromMidnight.set(Calendar.MINUTE, 0);
                fromMidnight.set(Calendar.SECOND, 0);
                fromMidnight.set(Calendar.MILLISECOND, 0);
                request.fromTime = (int) (itinerary.startTime.getTimeInMillis() - fromMidnight.getTimeInMillis()) / 1000 + 60;
                request.toTime = request.fromTime + 60;
            } else {
                break;
            }
        }
        return tripPlan;
    }

    private TripPlan routeMcrr(ProfileRequest request) {
        AvgTimer timerWorker = AvgTimer.timerMilliSec("SpeedTest:route  workerRoute");
        AvgTimer timerCollectResults = AvgTimer.timerMilliSec("SpeedTest:route  collect results");

        try {
            EgressAccessRouter streetRouter = new EgressAccessRouter(transportNetwork, request);
            streetRouter.route();

            // -------------------------------------------------------- [ WORKER ROUTE ]

            timerWorker.start();

            MultiCriteriaRangeRaptorWorker worker = new MultiCriteriaRangeRaptorWorker(
                    transportNetwork.transitLayer,
                    request,
                    streetRouter.accessTimesToStopsInSeconds
            );

            // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
            // Returns the total travel times as a 2D array of [searchIteration][destinationStopIndex].
            // Additional detailed path information is retained in the FastRaptorWorker after routing.
            worker.retainPaths = true;

            int[][] transitTravelTimesToStops = worker.route();

            timerWorker.stop();

            // -------------------------------------------------------- [ COLLECT RESULTS ]

            timerCollectResults.start();

            BestKnownResults results = new BestKnownResults(request.numberOfItineraries);

            TIntIntIterator egressTimeIterator = streetRouter.egressTimesToStopsInSeconds.iterator();

            int i = 0;
            while (egressTimeIterator.hasNext()) {
                egressTimeIterator.advance();
                int stopIndex = egressTimeIterator.key();
                int egressTime = egressTimeIterator.value();

                for (int range = 0; range < transitTravelTimesToStops.length; range++) {
                    int travelTimeToStop = transitTravelTimesToStops[range][stopIndex];

                    if (travelTimeToStop != MultiCriteriaRangeRaptorWorker.UNREACHED) {
                        int totalTime = travelTimeToStop + egressTime;
                        results.addResult(totalTime, range, stopIndex);
                        //accessTime = accessTimesToStopsInSeconds.get(worker.pathsPerIteration.get(range)[stopIndex].boardStops[0]);
                    }
                }
                ++i;
            }

            if (results.isEmpty()) {
                throw new IllegalStateException("NO RESULT FOUND");
            }

            TripPlan tripPlan = createTripPlanForRequest(request);

            for (BestResult result : results.results) {
                Path transitPath = result.path(worker.pathsPerIteration);
                Itinerary itinerary = createItinerary(request, streetRouter, transitPath);
                tripPlan.addItinerary(itinerary);
                tripPlan.sort();
            }
            timerCollectResults.stop();
            return tripPlan;
        } finally {
            timerWorker.fail();
            timerCollectResults.fail();
        }
    }

    private Itinerary createItinerary(ProfileRequest request, EgressAccessRouter streetRouter, Path path) {
        StreetPath accessPath = streetRouter.accessPath(path.boardStops[0]);
        StreetPath egressPath = streetRouter.egressPath(path.alightStops[path.alightStops.length - 1]);
        return itineraryMapper.createItinerary(request, path, accessPath, egressPath);
    }

    private void printError(CsvTestCase tc, long lapTime, Exception e) {
        printResult(-1, tc, lapTime, e.getMessage() + "  (" + e.getClass().getSimpleName() + ")");
    }

    private void printResult(int itineraries, CsvTestCase tc, long lapTime, String details) {
        String status = itineraries > 0 ? "SUCCESS" : "FAILED";
        LOG.info(String.format(
                "%-7s  %4d ms  %-66s %s",
                status,
                lapTime,
                tc.toString(),
                details
        ));
    }


    private static TripPlan createTripPlanForRequest(ProfileRequest request) {
        TripPlan tripPlan = new TripPlan();
        tripPlan.date = new Date(request.date.atStartOfDay(ZoneId.of("Europe/Oslo")).toInstant().toEpochMilli() + request.fromTime * 1000);
        tripPlan.from = new Place(request.fromLon, request.fromLat, "Origin");
        tripPlan.to = new Place(request.toLon, request.toLat, "Destination");
        return tripPlan;
    }

    /**
     * @return true if the search succeeded.
     */
    private ProfileRequest buildDefaultRequest(CsvTestCase testCase, SpeedTestCmdLineOpts opts) {
        ProfileRequest request = new ProfileRequest();

        request.accessModes = request.egressModes = request.directModes = EnumSet.of(LegMode.WALK);
        request.maxWalkTime = 20;
        request.maxTripDurationMinutes = 1200;
        request.transitModes = EnumSet.of(TransitModes.TRAM, TransitModes.SUBWAY, TransitModes.RAIL, TransitModes.BUS);
        request.description = testCase.origin + " -> " + testCase.destination;
        request.fromLat = testCase.fromLat;
        request.fromLon = testCase.fromLon;
        request.toLat = testCase.toLat;
        request.toLon = testCase.toLon;
        request.fromTime = 8 * 60 * 60; // 8AM in seconds since midnight
        request.toTime = request.fromTime + 60 * opts.searchWindowInMinutes();
        request.date = LocalDate.of(2018, 05, 25);
        request.numberOfItineraries = opts.numOfItineraries();
        return request;
    }

}
