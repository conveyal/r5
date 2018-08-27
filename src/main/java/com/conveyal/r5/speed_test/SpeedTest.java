package com.conveyal.r5.speed_test;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.SearchAlgorithm;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.profile.mcrr.PathParetoSortableWrapper;
import com.conveyal.r5.profile.mcrr.RaptorWorkerTransitDataProvider;
import com.conveyal.r5.profile.mcrr.TransitLayerRRDataProvider;
import com.conveyal.r5.profile.mcrr.Worker;
import com.conveyal.r5.speed_test.api.model.Itinerary;
import com.conveyal.r5.speed_test.api.model.Place;
import com.conveyal.r5.speed_test.api.model.TripPlan;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.AvgTimer;
import com.conveyal.r5.util.ParetoSet;
import gnu.trove.iterator.TIntIntIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.lang.ref.WeakReference;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.r5.profile.SearchAlgorithm.MultiCriteriaRangeRaptor;
import static com.conveyal.r5.profile.SearchAlgorithm.RangeRaptor;
import static com.conveyal.r5.util.TimeUtils.midnightOf;

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
    private final AvgTimer TOT_TIMER = AvgTimer.timerMilliSec("SpeedTest:route");
    private final AvgTimer TIMER_WORKER = AvgTimer.timerMilliSec("SpeedTest:route Worker");
    private final AvgTimer TIMER_COLLECT_RESULTS = AvgTimer.timerMilliSec("SpeedTest:route Collect Results");
    private final AvgTimer TIMER_COLLECT_RESULTS_ITINERARIES = AvgTimer.timerMilliSec("SpeedTest:route CR Itineraries");

    private List<Integer> numOfPathsFound = new ArrayList<>();
    private Map<ProfileFactory, List<Integer>> workerResults = new HashMap<>();
    private Map<ProfileFactory, List<Integer>> totalResults = new HashMap<>();

    /** Init profile used by the HttpServer */
    private ProfileFactory stateFactory = ProfileFactory.struct_arrays;

    SpeedTest(CommandLineOpts opts) throws Exception {
        this.opts = opts;
        initTransportNetwork();
    }

    public static void main(String[] args) throws Exception {
        AvgTimer.NOOP = false;
        new SpeedTest(new SpeedTestCmdLineOpts(args)).runTest();
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

    private void runTest() throws Exception {
        final SpeedTestCmdLineOpts opts = (SpeedTestCmdLineOpts) this.opts;
        final ProfileFactory[] strategies = opts.profiles();
        final int samples = opts.numberOfTestsSamplesToRun();

        initProfileStatistics();

        for (int i = 0; i < samples; ++i) {
            stateFactory = strategies[i % strategies.length];
            runSingleTest(opts);
        }
        printProfileStatistics();
    }

    private void runSingleTest(SpeedTestCmdLineOpts opts) throws Exception {
        List<CsvTestCase> testCases = CsvTestCase.readTestCasesFromFile(new File(this.opts.rootDir(), COORD_PAIRS));
        List<TripPlan> tripPlans = new ArrayList<>();


        int nSuccess = 0;
        numOfPathsFound.clear();

        // Force GC to avoid GC during the test
        forceGCToAvoidGCLater();

        // Warm up JIT compiler
        runSingleTestCase(tripPlans, testCases.get(9), opts);
        //if(true) return;
        runSingleTestCase(tripPlans, testCases.get(15), opts);


        LOG.info("\n- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - [ START " + stateFactory + " ]");

        AvgTimer.resetAll();

        for (CsvTestCase testCase : testCases) {
            nSuccess += runSingleTestCase(tripPlans, testCase, opts) ? 1 : 0;
        }

        int tcSize = testCases.size();

        LOG.info(
                "\n- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - [ SUMMARY " + stateFactory + " ]\n" +
                AvgTimer.listResults().stream().reduce("", (text, line) -> text + line + "\n") +
                "\n" +
                "\nPaths found: " + numOfPathsFound.stream().mapToInt((it) -> it).sum() + " " + numOfPathsFound +
                "\nSuccessful searches: " + nSuccess + " / " + tcSize +
                "\nTotal time: " + TOT_TIMER.totalTimeInSeconds() + " seconds" +
                (nSuccess == tcSize ? "" : "\n!!! UNEXPECTED RESULTS: " + (tcSize - nSuccess) + " OF " + tcSize + " FAILED. SEE LOG ABOVE FOR ERRORS !!!")
        );
        workerResults.get(stateFactory).add((int)TIMER_WORKER.avgTime());
        totalResults.get(stateFactory).add((int) TOT_TIMER.avgTime());
    }

    private void printProfileStatistics() {
        printProfileResults("Worker: ", workerResults);
        printProfileResults("Total:  ", totalResults);
    }

    private void initProfileStatistics() {
        for(ProfileFactory key : ProfileFactory.values()) {
            workerResults.put(key, new ArrayList<>());
            totalResults.put(key, new ArrayList<>());
        }
    }

    private boolean runSingleTestCase(List<TripPlan> tripPlans, CsvTestCase testCase, SpeedTestCmdLineOpts opts) {
        TripPlan route = null;
        try {
            final ProfileRequest request = buildDefaultRequest(testCase, opts);

            // Perform routing
            route = TOT_TIMER.timeAndReturn(() -> route(request) );

            tripPlans.add(route);

            testCase.assertResult(route.itinerariesAsCompactStrings());
            printResultOk(testCase, route, TOT_TIMER.lapTime(), opts.printItineraries());
            return true;
        }
        catch (Exception e) {
            printResultFailed(testCase, route, TOT_TIMER.lapTime(), e);
            return false;
        }
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
                return routeNewRRaptor(request);
        }
        throw new IllegalArgumentException("Algorithm not supported: " + request.algorithm);
    }


    private TripPlan routeRangeRaptor(ProfileRequest request) {
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
                Calendar fromMidnight =  midnightOf(itinerary.startTime);
                request.fromTime = (int) (itinerary.startTime.getTimeInMillis() - fromMidnight.getTimeInMillis()) / 1000 + 60;
                request.toTime = request.fromTime + 60;
            } else {
                break;
            }
        }
        return tripPlan;
    }

    private TripPlan routeNewRRaptor(ProfileRequest request) {
        try {
            EgressAccessRouter streetRouter = new EgressAccessRouter(transportNetwork, request);
            streetRouter.route();

            // -------------------------------------------------------- [ WORKER ROUTE ]

            TIMER_WORKER.start();


            RaptorWorkerTransitDataProvider transitData = new TransitLayerRRDataProvider(
                    transportNetwork.transitLayer, request.date, request.transitModes
            );
            final int nRounds = request.maxRides + 1;
            final int nStops = transportNetwork.transitLayer.getStopCount();



            Worker worker = stateFactory.createWorker(request, nRounds, nStops, transitData, streetRouter);

            Collection<Path> workerPaths = worker.route();

            TIMER_WORKER.stop();

            // -------------------------------------------------------- [ COLLECT RESULTS ]

            TIMER_COLLECT_RESULTS.start();

            /*
                TODO TGR We filter paths (ParetoSet) because the algorithm now return duplicates,
                we could probably do this with a HashSet instead, but the optimal solution is to fix the
                route iterator to only return new paths.
            */
            ParetoSet<PathParetoSortableWrapper> paths = new ParetoSet<>(PathParetoSortableWrapper.paretoDominanceFunctions());


            for (Path path : mpaths) {
                int egressTransferTime = streetRouter.egressTimesToStopsInSeconds.get(path.egressStop());
                int accessTransferTime = streetRouter.accessTimesToStopsInSeconds.get(path.accessStop());
                int totalTime = accessTransferTime + path.travelTime() + egressTransferTime;

                paths.add(new PathParetoSortableWrapper(path, totalTime));
            }


            if (paths.isEmpty()) {
                throw new IllegalStateException("NO RESULT FOUND");
            }

            numOfPathsFound.add(paths.size());

            TIMER_COLLECT_RESULTS_ITINERARIES.start();

            ItinerarySet itineraries = new ItinerarySet();

            for (PathParetoSortableWrapper transitPaths : paths.paretoSet()) {
                SpeedTestItinerary itinerary = createItinerary(request, streetRouter, transitPaths.path);

                itineraries.add(itinerary);
            }

            itineraries.filter();

            TIMER_COLLECT_RESULTS_ITINERARIES.stop();

            TripPlan tripPlan = createTripPlanForRequest(request);

            for (SpeedTestItinerary it : itineraries.iterator()) {
                tripPlan.addItinerary(it);
            }
            tripPlan.sort();

            TIMER_COLLECT_RESULTS.stop();

            return tripPlan;
        } finally {
            TIMER_WORKER.fail();
            TIMER_COLLECT_RESULTS.fail();
            TIMER_COLLECT_RESULTS_ITINERARIES.fail();
        }
    }

    private SpeedTestItinerary createItinerary(ProfileRequest request, EgressAccessRouter streetRouter, Path path) {
        StreetPath accessPath = streetRouter.accessPath(path.boardStops[0]);
        StreetPath egressPath = streetRouter.egressPath(path.alightStops[path.alightStops.length - 1]);
        return itineraryMapper.createItinerary(request, path, accessPath, egressPath);
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
        request.date = LocalDate.of(2018, 5, 25);
        request.numberOfItineraries = opts.numOfItineraries();
        return request;
    }


    private void printResultOk(CsvTestCase testCase, TripPlan route, long lapTime, boolean printItineraries) {
        printResult("SUCCESS", testCase, route, lapTime, printItineraries, "");
        testCase.logResultIfExpectedCsvInputIsMissing();
    }

    private void printResultFailed(CsvTestCase testCase, @Nullable TripPlan route, long lapTime, Exception e) {
        String errorDetails = " - " + e.getMessage() + "  (" + e.getClass().getSimpleName() + ")";
        printResult("FAILED", testCase, route, lapTime,true, errorDetails);
        if(route == null) {
            e.printStackTrace();
        }
    }


    private void printResult(String status, CsvTestCase tc, @Nullable TripPlan route, long lapTime, boolean printItineraries, String errorDetails) {
        if(printItineraries || tc.failed() || !tc.expectedCSVInputExist()) {
            System.err.printf(
                    "\nSpeedTest %-7s  %4d ms  %-66s %s %n",
                    status,
                    lapTime,
                    tc.toString(),
                    errorDetails
            );
        }
        if(printItineraries && route != null) {
            route.speedTestPrintItineraries();
        }
        if(tc.failed()) {
            System.err.println(tc.errorDetails());
        }
    }

    private static void printProfileResults(String header, Map<ProfileFactory, List<Integer>> result) {
        System.err.println();
        System.err.println(header);
        result.forEach((k,v) -> printProfileResultLine(k.name, v));
    }

    private static void printProfileResultLine(String label, List<Integer> v) {
        if(!v.isEmpty()) {
            System.err.printf(" ==> %-14s : %s Avg: %4.1f%n", label, v, v.stream().mapToInt(it -> it).average().orElse(0d));
        }
    }

    private void forceGCToAvoidGCLater() {
        WeakReference ref = new WeakReference<>(new Object());
        while(ref.get() != null) {
            System.gc();
        }
    }
}
