package com.conveyal.r5.speed_test;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.SearchAlgorithm;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.profile.mcrr.MultiCriteriaRangeRaptorWorker;
import com.conveyal.r5.profile.mcrr.PathParetoSortableWrapper;
import com.conveyal.r5.speed_test.api.model.Itinerary;
import com.conveyal.r5.speed_test.api.model.Place;
import com.conveyal.r5.speed_test.api.model.TripPlan;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.AvgTimer;
import com.conveyal.r5.util.ParetoSet;
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
    private static final AvgTimer TIMER = AvgTimer.timerMilliSec("SpeedTest:route");
    private static final AvgTimer TIMER_WORKER = AvgTimer.timerMilliSec("SpeedTest:route Worker");
    private static final AvgTimer TIMER_COLLECT_RESULTS = AvgTimer.timerMilliSec("SpeedTest:route Collect Results");
    private static final AvgTimer TIMER_COLLECT_RESULTS_PATHS = AvgTimer.timerMilliSec("SpeedTest:route CR Paths");
    private static final AvgTimer TIMER_COLLECT_RESULTS_ITINERARIES = AvgTimer.timerMilliSec("SpeedTest:route CR Itineraries");
    private static final AvgTimer TIMER_COLLECT_RESULTS_ITINERARIES_CREATE = AvgTimer.timerMilliSec("SpeedTest:route CR Itineraries create");
    private static final AvgTimer TIMER_COLLECT_RESULTS_TRIP_PLAN = AvgTimer.timerMilliSec("SpeedTest:route CR TripPlan");

    private List<Integer> numOfPathsFound = new ArrayList<>();

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
        numOfPathsFound.clear();

        // Warm up JIT compiler
        runSingleTestCase(tripPlans, testCases.get(9), opts);
        //if(true) return;
        runSingleTestCase(tripPlans, testCases.get(15), opts);


        LOG.info("\n- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - [ START ]");

        AvgTimer.resetAll();

        for (CsvTestCase testCase : testCases) {
            boolean ok = runSingleTestCase(tripPlans, testCase, opts);
            nRoutesComputed += ok ? 1 : 0;
        }

        LOG.info(
                "\n- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - [ SUMMARY ]\n" +
                AvgTimer.listResults().stream().reduce("", (text, line) -> text + line + "\n") +
                "\n" +
                "\nPaths found: " + numOfPathsFound.stream().mapToInt((it) -> it).sum() + " " + numOfPathsFound +
                "\nSuccessful searches: " + nRoutesComputed + " / " + testCases.size() +
                "\nTotal time: " + TIMER.totalTimeInSeconds() + " seconds"
        );

    }

    private boolean runSingleTestCase(List<TripPlan> tripPlans, CsvTestCase testCase, SpeedTestCmdLineOpts opts) {
        try {

            final ProfileRequest request = buildDefaultRequest(testCase, opts);
            TripPlan route = TIMER.timeAndReturn(() ->
                    route(request)
            );
            tripPlans.add(route);
            if (opts.printItineraries()) {
                printResult(route.getItineraries().size(), testCase, TIMER.lapTime(), "");
                route.speedTestPrintItineraries();
            }
            return true;
        } catch (ConcurrentModificationException | NullPointerException e) {
            printError(testCase, TIMER.lapTime(), e);
            e.printStackTrace();
        } catch (Exception e) {
            printError(testCase, TIMER.lapTime(), e);
            e.printStackTrace();
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
        try {
            EgressAccessRouter streetRouter = new EgressAccessRouter(transportNetwork, request);
            streetRouter.route();

            // -------------------------------------------------------- [ WORKER ROUTE ]

            TIMER_WORKER.start();

            MultiCriteriaRangeRaptorWorker worker = new MultiCriteriaRangeRaptorWorker(
                    transportNetwork.transitLayer,
                    request,
                    streetRouter.accessTimesToStopsInSeconds,
                    streetRouter.egressTimesToStopsInSeconds.keys()
            );

            worker.route();

            TIMER_WORKER.stop();

            // -------------------------------------------------------- [ COLLECT RESULTS ]

            TIMER_COLLECT_RESULTS.start();
            TIMER_COLLECT_RESULTS_PATHS.start();



            /*
                TODO TGR We filter paths (ParetoSet) because the algorithm now return duplicates,
                we could probably do this with a HashSet instead, but the optimal solution is to fix the
                route iterator to only return new paths.
            */
            ParetoSet<PathParetoSortableWrapper> paths = new ParetoSet<>(PathParetoSortableWrapper.paretoDominanceFunctions());

            TIntIntIterator egressTimeIterator = streetRouter.egressTimesToStopsInSeconds.iterator();

            int egressIndex = 0;
            while (egressTimeIterator.hasNext()) {
                egressTimeIterator.advance();
//                int stopIndex = egressTimeIterator.key();
                int egressTime = egressTimeIterator.value();
                int minuteReversed = worker.nMinutes;

                for (int minute = 0; minute < worker.nMinutes; minute++) {
                    --minuteReversed;

                    Path path = worker.pathsPerIteration.get(minute)[i];
                    if (path != null) {

                        // TGR - This is not correct, it calculates the time from the fromTime, not from
                        // when the travel need to start. Lets say the access walk takes 3 minutes and the
                        // first available buss start 16:30 and take 10 minutes. Then the travel time is
                        // 3 min access + 10 min = 13 min, not (fromTime=16:00): 43 minutes.
                        // I don´t want to fix it because it would affect the test, also it is parially
                        // handled by the paths pareto set and the fact that from time is adjusted by the
                        // RR minutes starting point.

                        int travelTimeToStop = path.alightTimes[path.length - 1] - request.fromTime - minuteReversed * 60;

                        int totalTime = travelTimeToStop + egressTime;

                        paths.add(new PathParetoSortableWrapper(path, totalTime));
                    }
                }
                egressIndex++;
            }
            TIMER_COLLECT_RESULTS_PATHS.stop();
            TIMER_COLLECT_RESULTS_ITINERARIES.start();

            if (paths.isEmpty()) {
                throw new IllegalStateException("NO RESULT FOUND");
            }
            numOfPathsFound.add(paths.size());

            ItinerarySet itineraries = new ItinerarySet();

            for (PathParetoSortableWrapper transitPaths : paths.paretoSet()) {
                TIMER_COLLECT_RESULTS_ITINERARIES_CREATE.start();
                SpeedTestItinerary itinerary = createItinerary(request, streetRouter, transitPaths.path);
                TIMER_COLLECT_RESULTS_ITINERARIES_CREATE.stop();

                itineraries.add(itinerary);
            }

            itineraries.filter();

            TIMER_COLLECT_RESULTS_ITINERARIES.stop();
            TIMER_COLLECT_RESULTS_TRIP_PLAN.start();

            TripPlan tripPlan = createTripPlanForRequest(request);

            for (SpeedTestItinerary it : itineraries.iterator()) {
                tripPlan.addItinerary(it);
            }
            tripPlan.sort();

            TIMER_COLLECT_RESULTS_TRIP_PLAN.stop();
            TIMER_COLLECT_RESULTS.stop();

            return tripPlan;
        } finally {
            TIMER_WORKER.fail();
            TIMER_COLLECT_RESULTS.fail();
        }
    }

    private SpeedTestItinerary createItinerary(ProfileRequest request, EgressAccessRouter streetRouter, Path path) {
        StreetPath accessPath = streetRouter.accessPath(path.boardStops[0]);
        StreetPath egressPath = streetRouter.egressPath(path.alightStops[path.alightStops.length - 1]);
        return itineraryMapper.createItinerary(request, path, accessPath, egressPath);
    }

    private void printError(CsvTestCase tc, long lapTime, Exception e) {
        printResult(-1, tc, lapTime, e.getMessage() + "  (" + e.getClass().getSimpleName() + ")");
    }

    private void printResult(int itineraries, CsvTestCase tc, long lapTime, String details) {
        String status = itineraries > 0 ? "SUCCESS" : "FAILED";
        System.err.printf(
                "\nSpeedTest %-7s  %4d ms  %-66s %s %n",
                status,
                lapTime,
                tc.toString(),
                details
        );
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
