package com.conveyal.r5.profile;

import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.transit.PickDropType;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.conveyal.r5.profile.FastRaptorWorker.FrequencyBoardingMode.HALF_HEADWAY;
import static com.conveyal.r5.profile.FastRaptorWorker.FrequencyBoardingMode.MONTE_CARLO;
import static com.conveyal.r5.profile.FastRaptorWorker.FrequencyBoardingMode.UPPER_BOUND;

/**
 * FastRaptorWorker is faster than the old RaptorWorker and made to be more maintainable.
 * It is simpler, as it only focuses on the transit network; see the Propagater class for the methods that extend
 * the travel times from the final transit stop of a trip out to the individual targets.
 *
 * The algorithm used herein is described in
 *
 * Conway, Matthew Wigginton, Andrew Byrd, and Marco van der Linden. “Evidence-Based Transit and Land Use Sketch Planning
 *   Using Interactive Accessibility Methods on Combined Schedule and Headway-Based Networks.” Transportation Research
 *   Record 2653 (2017). doi:10.3141/2653-06.
 *
 * Delling, Daniel, Thomas Pajor, and Renato Werneck. “Round-Based Public Transit Routing,” January 1, 2012.
 *   http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
 *
 * There is basic support for saving paths, so we can report how to reach a destination rather than just how long it takes.
 *
 * This class originated as a rewrite of our RAPTOR code that would use "thin workers", allowing computation by a
 * generic function-execution service like AWS Lambda. The gains in efficiency were significant enough that this is now
 * the way we do all analysis work. This system also accounts for pure-frequency routes by using Monte Carlo methods
 * (generating randomized schedules).
 *
 * TODO rename to remove "fast" and revise above comments, there is only one worker now.
 */
public class FastRaptorWorker {

    private static final Logger LOG = LoggerFactory.getLogger(FastRaptorWorker.class);

    /**
     * This value essentially serves as Infinity for ints - it's bigger than every other number.
     * It is the travel time to a transit stop or a target before that stop or target is ever reached.
     * Be careful when propagating travel times from stops to targets, adding anything to UNREACHED will cause overflow.
     */
    public static final int UNREACHED = Integer.MAX_VALUE;

    /**
     * Minimum time between alighting from one vehicle and boarding another, in seconds.
     * TODO make this configurable, and use loop-transfers from transfers.txt.
     */
    public static final int BOARD_SLACK_SECONDS = 60;

    public static final int SECONDS_PER_MINUTE = 60;

    /**
     * Step for departure times. Use caution when changing this as we use the functions
     * request.getTimeWindowLengthMinutes and request.getMonteCarloDrawsPerMinute below which assume this value is 1
     * minute. The same functions are also used in BootstrappingTravelTimeReducer where we assume that their product is
     * the number of iterations performed.
     */
    private static final int DEPARTURE_STEP_SEC = 60;

    /**
     * Minimum wait for boarding to account for schedule variation.
     * FIXME clarify why this is separate from BOARD_SLACK. If it is not, merge the two constants into BOARD_SLACK_SEC.
     */
    private static final int MINIMUM_BOARD_WAIT_SEC = 60;

    /** The width of the departure time window in minutes. */
    public final int nMinutes;

    /**
     * The number of different randomized schedules to create at each departure minute for frequency-based routes.
     * When frequency routes (non-scheduled routes) are present, we perform multiple searches per departure minute
     * using different randomly-offset schedules (a Monte Carlo exploration of all possible schedules). This field
     * controls how many such randomly offset schedules are generated. A value of 0 is a special case that triggers
     * the HALF_HEADWAY boarding assumption, which will lead one iteration per minute to be returned.
     */
    public final int monteCarloDrawsPerMinute;

    // Variables to track calculation time spent, all in nanoseconds (some of the operations we're timing are
    // significantly submillisecond) although using ms would be fine because the number of times we cross
    // a millisecond boundary would be proportional to the portion of a millisecond that operation took.
    // TODO a Stopwatch class that can time arbitrarily named events with a TObjectIntMap.

    public long startClockTime;
    public long timeInScheduledSearch;
    public long timeInScheduledSearchTransit;
    public long timeInScheduledSearchFrequencyBounds;
    public long timeInScheduledSearchTransfers;

    public long timeInFrequencySearch;
    public long timeInFrequencySearchFrequency;
    public long timeInFrequencySearchScheduled;
    public long timeInFrequencySearchTransfers;

    /** The transit layer to route on. */
    private final TransitLayer transit;

    /** Times to access each transit stop using the street network (seconds). */
    private final TIntIntMap accessStops;

    /** The routing parameters. */
    private final ProfileRequest request;

    /** Frequency-based trip patterns running on a given day */
    private TripPattern[] runningFrequencyPatterns;

    /** Schedule-based trip patterns running on a given day */
    private TripPattern[] runningScheduledPatterns;

    /** Map from internal, filtered frequency pattern indices back to original pattern indices for frequency patterns */
    private int[] originalPatternIndexForFrequencyIndex;

    /** Map from internal, filtered pattern indices back to original pattern indices for scheduled patterns */
    private int[] originalPatternIndexForScheduledIndex;

    /** Array mapping from original pattern indices to the filtered frequency indices */
    private int[] frequencyIndexForOriginalPatternIndex;

    /** Array mapping from original pattern indices to the filtered scheduled indices */
    private int[] scheduledIndexForOriginalPatternIndex;

    private FrequencyRandomOffsets offsets;

    /** Services active on the date of the search */
    private final BitSet servicesActive;

    /**
     * The state resulting from the scheduled search at a particular departure minute.
     * This state is reused at each departure minute without re-initializng it (this is the range-raptor optimization).
     * The randomized schedules at each departure minute are applied on top of this scheduled state.
     */
    private final RaptorState[] scheduleState;

    /**
     * This should be either HALF_HEADWAY or MONTE_CARLO.
     * The othe value UPPER_BOUND is only used within a sub-search of MONTE_CARLO.
     */
    private final FrequencyBoardingMode boardingMode;

    /** Set to true to save path details for all optimal paths. */
    public boolean retainPaths = false;

    /** If we're going to store paths to every destination (e.g. for static sites) then they'll be retained here. */
    public List<Path[]> pathsPerIteration;

    public FastRaptorWorker (TransitLayer transitLayer, ProfileRequest request, TIntIntMap accessStops) {
        this.transit = transitLayer;
        this.request = request;
        this.accessStops = accessStops;
        this.servicesActive  = transit.getActiveServicesForDate(request.date);
        // we add one to request.maxRides, first state is result of initial walk
        this.scheduleState = IntStream.range(0, request.maxRides + 1)
                .mapToObj((i) -> new RaptorState(
                            transit.getStopCount(),
                            request.maxTripDurationMinutes * SECONDS_PER_MINUTE))
                .toArray(RaptorState[]::new);

        for (int i = 1; i < this.scheduleState.length; i++) {
            this.scheduleState[i].previous = this.scheduleState[i - 1];
        }

        offsets = new FrequencyRandomOffsets(transitLayer);

        // compute number of minutes for scheduled search
        nMinutes = request.getTimeWindowLengthMinutes();

        // how many monte carlo draws per minute of scheduled search to get desired total iterations?
        monteCarloDrawsPerMinute = request.getMonteCarloDrawsPerMinute();

        // Zero Monte Carlo draws means use half-headway instead of Monte Carlo randomization.
        boardingMode = (monteCarloDrawsPerMinute == 0) ? HALF_HEADWAY : MONTE_CARLO;
    }

    /**
     * For each iteration (minute + MC draw combination), return the minimum travel time to each transit stop in seconds.
     * Return value dimension order is [searchIteration][transitStopIndex]
     *
     * TODO Create proper types for return values?
     */
    public int[][] route () {

        startClockTime = System.nanoTime();
        prefilterPatterns();

        // Initialize result storage.
        // Results are one arrival time at each stop, for every raptor iteration.
        int nIterations;
        if (boardingMode == HALF_HEADWAY) {
            nIterations = nMinutes;
            LOG.info("Performing {} scheduled iterations using half-headway for frequency routes.", nMinutes);
        } else {
            nIterations = monteCarloDrawsPerMinute * nMinutes;
            LOG.info("Performing {} scheduled iterations each with {} Monte Carlo draws for a total of {} iterations.",
                    nMinutes, monteCarloDrawsPerMinute, nIterations);
        }
        int[][] arrivalTimesAtStopsPerIteration = new int[nIterations][];
        if (retainPaths) pathsPerIteration = new ArrayList<>();
        int currentIteration = 0;

        // The main outer loop iterates backward over all minutes in the departure times window.
        for (int departureTime = request.toTime - DEPARTURE_STEP_SEC, minute = nMinutes;
             departureTime >= request.fromTime;
             departureTime -= DEPARTURE_STEP_SEC, minute--) {

            if (minute % 15 == 0) LOG.debug("  minute {}", minute);

            // Run the raptor search. For this particular departure time, we receive N arrays of arrival times at all
            // stops, one for each randomized schedule: resultsForMinute[randScheduleNumber][transitStop]
            int[][] resultsForMinute = runRaptorForMinute(departureTime);

            // Bypass Java's "effectively final" nonsense.
            // FIXME we could avoid this "final" weirdness by just using non-stream explicit loop syntax over the stops.
            // TODO clarify identifiers and explain how results are being unrolled from minutes into 'iterations'.
            final int finalDepartureTime = departureTime;
            for (int[] arrivalTimesAtStops : resultsForMinute) {
                // NB this copies the array, so we don't have issues with it being updated later
                arrivalTimesAtStopsPerIteration[currentIteration++] = IntStream.of(arrivalTimesAtStops)
                        .map(r -> r != UNREACHED ? r - finalDepartureTime : r)
                        .toArray();
            }
        }

        LOG.debug("Search completed in {}s", (System.nanoTime() - startClockTime) / 1e9d);
        LOG.debug("Scheduled/bounds search: {}s", timeInScheduledSearch / 1e9d);
        LOG.debug("  - Scheduled search: {}s", timeInScheduledSearchTransit / 1e9d);
        LOG.debug("  - Frequency upper bounds: {}s", timeInScheduledSearchFrequencyBounds / 1e9d);
        LOG.debug("  - Transfers: {}s", timeInScheduledSearchTransfers / 1e9d);
        LOG.debug("Frequency search: {}s", timeInFrequencySearch / 1e9d);
        LOG.debug("  - Frequency component: {}s", timeInFrequencySearchFrequency / 1e9d);
        LOG.debug("  - Resulting updates to scheduled component: {}s", timeInFrequencySearchScheduled / 1e9d);
        LOG.debug("  - Transfers: {}s", timeInFrequencySearchTransfers / 1e9d);

        return arrivalTimesAtStopsPerIteration;
    }

    /** Prefilter the patterns to only ones that are running */
    private void prefilterPatterns () {
        TIntList frequencyPatterns = new TIntArrayList();
        TIntList scheduledPatterns = new TIntArrayList();
        frequencyIndexForOriginalPatternIndex = new int[transit.tripPatterns.size()];
        Arrays.fill(frequencyIndexForOriginalPatternIndex, -1);
        scheduledIndexForOriginalPatternIndex = new int[transit.tripPatterns.size()];
        Arrays.fill(scheduledIndexForOriginalPatternIndex, -1);

        int patternIndex = -1; // first increment lands at 0
        int frequencyIndex = 0;
        int scheduledIndex = 0;
        for (TripPattern pattern : transit.tripPatterns) {
            patternIndex++;
            RouteInfo routeInfo = transit.routes.get(pattern.routeIndex);
            TransitModes mode = TransitLayer.getTransitModes(routeInfo.route_type);
            if (pattern.servicesActive.intersects(servicesActive) && request.transitModes.contains(mode)) {
                // at least one trip on this pattern is relevant, based on the profile request's date and modes
                if (pattern.hasFrequencies) {
                    frequencyPatterns.add(patternIndex);
                    frequencyIndexForOriginalPatternIndex[patternIndex] = frequencyIndex++;
                }
                if (pattern.hasSchedules) { // NB not else b/c we still support combined frequency and schedule patterns.
                    scheduledPatterns.add(patternIndex);
                    scheduledIndexForOriginalPatternIndex[patternIndex] = scheduledIndex++;
                }
            }
        }

        originalPatternIndexForFrequencyIndex = frequencyPatterns.toArray();
        originalPatternIndexForScheduledIndex = scheduledPatterns.toArray();

        runningFrequencyPatterns = IntStream.of(originalPatternIndexForFrequencyIndex)
                .mapToObj(transit.tripPatterns::get).toArray(TripPattern[]::new);
        runningScheduledPatterns = IntStream.of(originalPatternIndexForScheduledIndex)
                .mapToObj(transit.tripPatterns::get).toArray(TripPattern[]::new);

        LOG.info("Prefiltering patterns based on date active reduced {} patterns to {} frequency and {} scheduled patterns",
                transit.tripPatterns.size(), frequencyPatterns.size(), scheduledPatterns.size());
    }

    /**
     * Set the departure time in the scheduled search to the given departure time,
     * and prepare for the scheduled search at the next-earlier minute
     */
    private void advanceScheduledSearchToPreviousMinute (int nextMinuteDepartureTime) {
        for (RaptorState state : this.scheduleState) {
            state.setDepartureTime(nextMinuteDepartureTime);
            // clear all touched stops to avoid constant reëxploration
            state.bestStopsTouched.clear();
            state.nonTransferStopsTouched.clear();
            // TODO prune trips that are now longer than max lengths to avoid biasing averages
        }
        // add initial stops
        RaptorState initialState = scheduleState[0];
        accessStops.forEachEntry((stop, accessTime) -> {
            initialState.setTimeAtStop(stop, accessTime + nextMinuteDepartureTime, -1, -1, 0, 0, true);
            return true; // continue iteration
        });
    }

    /**
     * Perform a RAPTOR search at one specific departure time (at one specific minute). A full range-RAPTOR search
     * consists of many such searches at different departure times, typically working backward from the end of a time
     * window toward its beginning, and reusing state between searches as an optimization.
     *
     * @param departureTime       When this search departs.
     * @return an array of length iterationsPerMinute, containing the arrival (clock) times at each stop for each
     * iteration.
     */
    private int[][] runRaptorForMinute (int departureTime) {
        advanceScheduledSearchToPreviousMinute(departureTime);

        int iterationsPerMinute = (boardingMode == MONTE_CARLO) ? monteCarloDrawsPerMinute : 1;

        // Run a Raptor search for only the scheduled routes (not the frequency-based routes).
        // The initial round 0 holds the results of the street search: the travel times to transit stops from the origin
        // using the non-transit access mode(s).
        // We are using the Range-RAPTOR extension described in Delling, Daniel, Thomas Pajor, and Renato Werneck.
        // “Round-Based Public Transit Routing,” January 1, 2012. http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
        // In this optimization, we re-use the arrival times found by searches that depart later, because the arrival
        // time at each location at time t + n is an upper bound on the arrival time departing at time t.
        if (transit.hasSchedules) {
            long startTime = System.nanoTime();
            for (int round = 1; round <= request.maxRides; round++) {
                // We always process the full number of rounds requested, since we have "transfer limiting".
                // We don't optimize by breaking out of the loop early when no transfers lead to any improvement,
                // because that is rare and complicates the code that handles the results.
                // TODO explain what "transfer limiting" is.

                // Prevent finding crazy multi-transfer ways to get somewhere when there is a quicker way with fewer
                // transfers. TODO explain this mechanism.
                scheduleState[round].min(scheduleState[round - 1]);

                long scheduledStartTime = System.nanoTime();
                doScheduledSearchForRound(scheduleState[round - 1], scheduleState[round]);
                timeInScheduledSearchTransit += System.nanoTime() - scheduledStartTime;

                // If there are frequency routes, we will be randomizing the offsets of those routes.
                // First perform a frequency search using worst-case boarding time to provide a tighter upper bound on
                // total travel time. Each randomized schedule will improve on these travel times.
                // This is only helpful for Monte Carlo, not half-headway. Even in Monte Carlo mode,
                // perhaps we should only do it when iterationsPerMinute is high (2 or more?).
                if (transit.hasFrequencies && boardingMode == MONTE_CARLO) {
                    long frequencyStartTime = System.nanoTime();
                    doFrequencySearchForRound(scheduleState[round - 1], scheduleState[round], UPPER_BOUND);
                    timeInScheduledSearchFrequencyBounds += System.nanoTime() - frequencyStartTime;
                }

                long transferStartTime = System.nanoTime();
                doTransfers(scheduleState[round]);
                timeInScheduledSearchTransfers += System.nanoTime() - transferStartTime;
            }
            timeInScheduledSearch += System.nanoTime() - startTime;
        }

        // now run frequency searches using randomized schedules for all frequency lines. We use the scheduled search
        // and the worst-case boarding time of all frequency routes as an upper bound on the frequency search, so we are
        // copying the arrival times from the just completed search. This is our key innovation, described in
        // Conway, Byrd, and van der Linden 2017.
        if (transit.hasFrequencies) {
            long startTime = System.nanoTime();
            int[][] result = new int[iterationsPerMinute][];
            // Each iteration is a fresh Monte Carlo draw (randomization of frequency route offsets).
            // In half-headway mode, only one iteration will happen with no randomization.
            for (int iteration = 0; iteration < iterationsPerMinute; iteration++) {
                // At each iteration, we make a copy of the upper bound state.
                RaptorState[] frequencyState = Stream.of(scheduleState).map((s) -> s.copy()).toArray(RaptorState[]::new);
                for (int i = 1; i < frequencyState.length; i++) {
                    frequencyState[i].previous = frequencyState[i - 1];
                }

                if (boardingMode == MONTE_CARLO) {
                    // Take a new Monte Carlo draw if requested (i.e. if boarding assumption is not half-headway): for
                    // each frequency-based route, choose how long after service starts the first vehicle leaves (the
                    // route's "phase"). We run all Raptor rounds with one draw before proceeding to the next draw.
                    offsets.randomize();
                }

                for (int round = 1; round <= request.maxRides; round++) {
                    frequencyState[round].min(frequencyState[round - 1]);

                    // Scheduled search. This is improving on travel times from stops touched by frequency routes or
                    // combinations of frequency and scheduled routes.
                    long scheduledStart = System.nanoTime();
                    doScheduledSearchForRound(frequencyState[round - 1], frequencyState[round]);
                    timeInFrequencySearchScheduled += System.nanoTime() - scheduledStart;

                    // frequency search: additionally use stops touched by scheduled search
                    // okay to destructively modify last round frequency state, it will not be used after this
                    long frequencyStart = System.nanoTime();
                    frequencyState[round - 1].bestStopsTouched.or(scheduleState[round - 1].bestStopsTouched);
                    frequencyState[round - 1].nonTransferStopsTouched.or(scheduleState[round - 1].nonTransferStopsTouched);
                    doFrequencySearchForRound(frequencyState[round - 1], frequencyState[round], boardingMode);

                    timeInFrequencySearchFrequency += System.nanoTime() - frequencyStart;

                    long transferStart = System.nanoTime();
                    doTransfers(frequencyState[round]);
                    timeInFrequencySearchTransfers += System.nanoTime() - transferStart;
                }
                // We are processing frequency routes, states are already a copy of the retained scheduled search state,
                // no need to make an additional protective copy.
                RaptorState finalRoundState = frequencyState[request.maxRides];
                result[iteration] = finalRoundState.bestNonTransferTimes;
                if (retainPaths) {
                    pathsPerIteration.add(pathToEachStop(finalRoundState));
                }
            }
            timeInFrequencySearch += System.nanoTime() - startTime;
            return result;
        } else {
            // If there are no frequency trips, return the result of the scheduled search, but repeated as many times
            // as there are requested MC draws, so that the scheduled search accessibility avoids potential bugs
            // where assumptions are made about how many results will be returned from a search, e.g., in
            // https://github.com/conveyal/r5/issues/306
            // FIXME on large networks with no frequency routes this seems extremely inefficient.
            // It may be somewhat less inefficient than it seems if we make arrays of references all to the same object.
            // TODO check whether we're actually hitting this code with iterationsPerMinute > 1 on scheduled networks.
            int[][] result = new int[iterationsPerMinute][];
            RaptorState finalRoundState = scheduleState[request.maxRides];
            // This scheduleState is repeatedly modified as the outer loop progresses over departure minutes.
            // We have to be careful here that creating these paths does not modify the state, and makes
            // protective copies of any information we want to retain.
            Path[] paths = retainPaths ? pathToEachStop(finalRoundState) : null;
            for (int iteration = 0; iteration < iterationsPerMinute; iteration++) {
                result[iteration] = finalRoundState.bestNonTransferTimes;
                if (retainPaths) {
                    pathsPerIteration.add(paths);
                }
            }
            return result;
        }
    }

    /**
     * Create the optimal path to each stop in the transit network, based on the given RaptorState.
     */
    private static Path[] pathToEachStop (RaptorState state) {
        int nStops = state.bestNonTransferTimes.length;
        Path[] paths = new Path[nStops];
        for (int s = 0; s < nStops; s++) {
            if (state.bestNonTransferTimes[s] == UNREACHED) {
                paths[s] = null;
            } else {
                paths[s] = new Path(state, s);
            }
        }
        return paths;
    }

    /**
     * A sub-step in the process of performing a RAPTOR search at one specific departure time (at one specific minute).
     * This method handles only the routes that have exact schedules. There is another method that handles only the
     * other kind of routes: the frequency-based routes.
     */
    private void doScheduledSearchForRound(RaptorState inputState, RaptorState outputState) {
        BitSet patternsTouched = getPatternsTouchedForStops(inputState, scheduledIndexForOriginalPatternIndex);

        for (int patternIndex = patternsTouched.nextSetBit(0); patternIndex >= 0; patternIndex = patternsTouched.nextSetBit(patternIndex + 1)) {
            int originalPatternIndex = originalPatternIndexForScheduledIndex[patternIndex];
            TripPattern pattern = runningScheduledPatterns[patternIndex];
            int onTrip = -1;
            int waitTime = 0;
            int boardTime = 0;
            int boardStop = -1;
            TripSchedule schedule = null;

            for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.stops.length; stopPositionInPattern++) {
                int stop = pattern.stops[stopPositionInPattern];

                // attempt to alight if we're on board and if drop off is allowed, done above the board search so
                // that we don't check for alighting when boarding
                if (onTrip > -1 && pattern.dropoffs[stopPositionInPattern] != PickDropType.NONE) {
                    int alightTime = schedule.arrivals[stopPositionInPattern];
                    int onVehicleTime = alightTime - boardTime;

                    if (waitTime + onVehicleTime + inputState.bestTimes[boardStop] > alightTime) {
                        LOG.error("Components of travel time are larger than travel time!");
                    }

                    outputState.setTimeAtStop(stop, alightTime, originalPatternIndex, boardStop, waitTime, onVehicleTime, false);
                }

                int sourcePatternIndex = inputState.previousStop[stop] == -1 ?
                        inputState.previousPatterns[stop] :
                        inputState.previousPatterns[inputState.previousStop[stop]];

                // Don't attempt to board if this stop was not reached in the last round or if pick up is not allowed,
                // and don't attempt to reboard the same pattern
                if (inputState.bestStopsTouched.get(stop) && sourcePatternIndex != originalPatternIndex && pattern.pickups[stopPositionInPattern] != PickDropType.NONE) {
                    int earliestBoardTime = inputState.bestTimes[stop] + MINIMUM_BOARD_WAIT_SEC;

                    // only attempt to board if the stop was touched
                    if (onTrip == -1) {
                        if (inputState.bestStopsTouched.get(stop)) {
                            int candidateTripIndex = -1;
                            EARLIEST_TRIP:
                            for (TripSchedule candidateSchedule : pattern.tripSchedules) {
                                candidateTripIndex++;

                                if (!servicesActive.get(candidateSchedule.serviceCode) || candidateSchedule.headwaySeconds != null) {
                                    // frequency trip or not running
                                    continue;
                                }

                                if (earliestBoardTime < candidateSchedule.departures[stopPositionInPattern]) {
                                    // board this vehicle
                                    onTrip = candidateTripIndex;
                                    schedule = candidateSchedule;
                                    boardTime = candidateSchedule.departures[stopPositionInPattern];
                                    waitTime = boardTime - inputState.bestTimes[stop];
                                    boardStop = stop;
                                    break EARLIEST_TRIP;
                                }
                            }
                        }
                    } else {
                        // check if we can back up to an earlier trip due to this stop being reached earlier
                        int bestTripIdx = onTrip;
                        while (--bestTripIdx >= 0) {
                            TripSchedule trip = pattern.tripSchedules.get(bestTripIdx);
                            if (trip.headwaySeconds != null || !servicesActive.get(trip.serviceCode)) {
                                // This is a frequency trip or it is not running on the day of the search.
                                continue;
                            }
                            if (trip.departures[stopPositionInPattern] > earliestBoardTime) {
                                onTrip = bestTripIdx;
                                schedule = trip;
                                boardTime = trip.departures[stopPositionInPattern];
                                waitTime = boardTime - inputState.bestTimes[stop];
                                boardStop = stop;
                            } else {
                                // this trip arrives too early, break loop since they are sorted by departure time
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The different ways of determining the wait time before boarding a vehicle for frequency-based routes.
     * Would it be more efficient to pass in a function reference, after defining a new departure search interface?
     * e.g. public interface frequencyDepartCalculator { ... }
     */
    public enum FrequencyBoardingMode {
        /** The primary frequency search, using various randomized departure offsets for each route. */
        MONTE_CARLO,
        /** Compute a deterministic upper bound, which helps speed up subsequent frequency searches. */
        UPPER_BOUND,
        /** Assume a vehicle always comes after exactly half the headway, for comparison with Monte Carlo. */
        HALF_HEADWAY
    }

    /**
     * Do a frequency search. If computeDeterministicUpperBound is true, worst-case frequency boarding time will be used
     * so that the output of this function can be used in a range-RAPTOR search. Otherwise Monte Carlo schedules will be
     * used to improve upon the output of the range-RAPTOR bounds search.
     *
     * @param frequencyBoardingMode see comments on enum values.
     */
    private void doFrequencySearchForRound(
            RaptorState inputState,
            RaptorState outputState,
            FrequencyBoardingMode frequencyBoardingMode
    ) {
        BitSet patternsTouched = getPatternsTouchedForStops(inputState, frequencyIndexForOriginalPatternIndex);
        for (int patternIndex = patternsTouched.nextSetBit(0); patternIndex >= 0; patternIndex = patternsTouched.nextSetBit(patternIndex + 1)) {
            TripPattern pattern = runningFrequencyPatterns[patternIndex];

            int tripScheduleIndex = -1; // first increment lands at 0
            for (TripSchedule schedule : pattern.tripSchedules) {
                tripScheduleIndex++;

                // scheduled trip or not running
                if (!servicesActive.get(schedule.serviceCode) || schedule.headwaySeconds == null) continue;

                for (int frequencyEntryIdx = 0; frequencyEntryIdx < schedule.headwaySeconds.length; frequencyEntryIdx++) {
                    int originalPatternIndex = originalPatternIndexForFrequencyIndex[patternIndex];

                    int boardTime = -1;
                    int boardStopPositionInPattern = -1;
                    int waitTime = -1;

                    for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.stops.length; stopPositionInPattern++) {
                        int stop = pattern.stops[stopPositionInPattern];

                        // attempt to alight if boarded and if drop off is allowed
                        if (boardTime > -1 && pattern.dropoffs[stopPositionInPattern] != PickDropType.NONE) {
                            // attempt to alight
                            int travelTime = schedule.arrivals[stopPositionInPattern] - schedule.departures[boardStopPositionInPattern];
                            int alightTime = boardTime + travelTime;
                            int boardStop = pattern.stops[boardStopPositionInPattern];
                            outputState.setTimeAtStop(stop, alightTime, originalPatternIndex, boardStop, waitTime, travelTime, false);
                        }

                        // attempt to board if pick up is allowed
                        // (even if already boarded, since this is a frequency trip and we could move back)
                        if (inputState.bestStopsTouched.get(stop) && pattern.pickups[stopPositionInPattern] != PickDropType.NONE) {
                            int earliestBoardTime = inputState.bestTimes[stop] + MINIMUM_BOARD_WAIT_SEC;

                            // if we're computing the upper bound, we want the worst case. This is the only thing that is
                            // valid in a range RAPTOR search; using random schedule draws in range RAPTOR would be problematic
                            // because they need to be independent across minutes.

                            final int newBoardingDepartureTimeAtStop;

                            if (frequencyBoardingMode == MONTE_CARLO) {
                                int offset = offsets.offsets.get(originalPatternIndex)[tripScheduleIndex][frequencyEntryIdx];
                                newBoardingDepartureTimeAtStop = getRandomFrequencyDepartureTime(
                                        schedule,
                                        stopPositionInPattern,
                                        offset,
                                        frequencyEntryIdx,
                                        earliestBoardTime
                                );
                            } else if (frequencyBoardingMode == UPPER_BOUND) {
                                newBoardingDepartureTimeAtStop = getWorstCaseFrequencyDepartureTime(
                                        schedule,
                                        stopPositionInPattern,
                                        frequencyEntryIdx,
                                        earliestBoardTime
                                );
                            } else if (frequencyBoardingMode == HALF_HEADWAY){
                                  newBoardingDepartureTimeAtStop = getAverageCaseFrequencyDepartureTime(
                                          schedule,
                                          stopPositionInPattern,
                                          frequencyEntryIdx,
                                          earliestBoardTime
                                  );
                            } else {
                                throw new AssertionError("Unknown departure search method.");
                            }
                            int remainOnBoardDepartureTimeAtStop = Integer.MAX_VALUE;

                            if (boardTime > -1) {
                                // We cannot re-use the calculation from above. We're using departure time at this stop
                                // here to account for any dwell time. TODO clarify what that means and how this works.
                                int travelTime = schedule.departures[stopPositionInPattern]
                                        - schedule.departures[boardStopPositionInPattern];
                                remainOnBoardDepartureTimeAtStop = boardTime + travelTime;
                            }
                            if (newBoardingDepartureTimeAtStop > -1
                                    && newBoardingDepartureTimeAtStop < remainOnBoardDepartureTimeAtStop) {
                                // board this trip
                                boardTime = newBoardingDepartureTimeAtStop;
                                waitTime = boardTime - inputState.bestTimes[stop];
                                boardStopPositionInPattern = stopPositionInPattern;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * @param earliestTime the time at or after which to find a departure time.
     * @return the earliest departure time on a particular scheduled frequency entry, or -1 if the frequency entry is
     * not usable.
     */
    public int getRandomFrequencyDepartureTime (
            TripSchedule schedule,
            int stopPositionInPattern,
            int offset,
            int frequencyEntryIdx,
            int earliestTime
    ) {
        // earliest board time is start time plus travel time plus offset
        int earliestBoardTimeThisEntry = schedule.startTimes[frequencyEntryIdx] +
                schedule.departures[stopPositionInPattern] +
                offset;

        // compute the number of trips on this entry
        // We take the difference between the end time and the start time including the offset
        // to get the time between the first trip and the last possible trip. We int-divide by the
        // headway and add one to correct for the fencepost problem.
        int numberOfTripsThisEntry = (schedule.endTimes[frequencyEntryIdx] -
                (schedule.startTimes[frequencyEntryIdx] + offset)) /
                schedule.headwaySeconds[frequencyEntryIdx] + 1;

        // the earliest time we can leave this stop based on when we arrived
        // We subtract one because we find trips that have departure time > this time, not
        // >=
        int lowerBoundBoardTime = earliestTime - 1;
        int earliestFeasibleTripIndexThisEntry;
        if (lowerBoundBoardTime <= earliestBoardTimeThisEntry) {
            earliestFeasibleTripIndexThisEntry = 0;
        } else {
            // find earliest trip later than the lower bound on board time
            // We add one because int math floors the result.
            // This is why we subtracted one second above, so that if the earliest board time
            // is exactly the second when the trip arrives, we will find that trip rather than the
            // next trip when we add one.
            earliestFeasibleTripIndexThisEntry =
                    (lowerBoundBoardTime - earliestBoardTimeThisEntry) / schedule.headwaySeconds[frequencyEntryIdx] + 1;
        }

        if (earliestFeasibleTripIndexThisEntry < numberOfTripsThisEntry) {
            return earliestBoardTimeThisEntry + earliestFeasibleTripIndexThisEntry * schedule.headwaySeconds[frequencyEntryIdx];
        } else {
            return -1;
        }
    }

    public int getWorstCaseFrequencyDepartureTime (TripSchedule schedule, int stopPositionInPattern, int frequencyEntryIdx, int earliestTime) {
        int headway = schedule.headwaySeconds[frequencyEntryIdx];
        int travelTimeFromStartOfTrip = schedule.departures[stopPositionInPattern];
        // The last vehicle could leave the terminal as early as headwaySeconds before the end of the frequency entry.
        int earliestEndTimeOfFrequencyEntry = schedule.endTimes[frequencyEntryIdx] - headway + travelTimeFromStartOfTrip;

        if (earliestEndTimeOfFrequencyEntry < earliestTime) return -1;

        // board pessimistically assuming the entry is already running
        int latestBoardTimeAssumingEntryIsAlreadyRunning = earliestTime + headway;
        // figure out the latest departure time of this trip at this stop
        int latestBoardTimeOfFirstTrip = schedule.startTimes[frequencyEntryIdx] + headway + travelTimeFromStartOfTrip;
        // return the max of those two
        return Math.max(latestBoardTimeAssumingEntryIsAlreadyRunning, latestBoardTimeOfFirstTrip);
    }

    /**
     * For half-headway (non-monte-carlo) evaluation of frequency-based routes. The caller should be looping through
     * all frequency entries (e.g. to allow a passenger to wait at a stop for a subsequent frequency entry to start).
     *
     * The departure time is assumed to be half-headway after the later (maximum) of the passenger's earliest possible
     * boarding time at the stop or the frequency entry's earliest possible arrival at the stop.
     *
     * TODO account for possible dwell time?
     *
     * @param earliestTime the time at or after which to find a departure time (i.e. when a passenger is
     *                    ready to board).
     *
     * @return clock time at which a passenger boards this frequency entry at this stop
     */
    public static int getAverageCaseFrequencyDepartureTime (
            TripSchedule schedule,
            int stopPositionInPattern,
            int frequencyEntryIdx,
            int earliestTime
    ) {
        int travelTimeFromStartOfTrip = schedule.departures[stopPositionInPattern];

        // Ensure the schedule has not ceased at this stop. Note that this approach assumes no trip for this frequency
        // entry leaves the first stop of the pattern after end_time, which is different from the assumption in the
        // approaches above. See discussion in issue #122
        int frequencyEndsAtThisStop = schedule.endTimes[frequencyEntryIdx] + travelTimeFromStartOfTrip;
        if (frequencyEndsAtThisStop < earliestTime) {
            return -1;
        }

        int frequencyStartsAtThisStop = schedule.startTimes[frequencyEntryIdx] + travelTimeFromStartOfTrip;

        int headway = schedule.headwaySeconds[frequencyEntryIdx];
        int halfHeadway = headway / 2;

        return halfHeadway + (Math.max(earliestTime, frequencyStartsAtThisStop));

    }

    private void doTransfers (RaptorState state) {
        // avoid integer casts in tight loop below
        int walkSpeedMillimetersPerSecond = (int) (request.walkSpeed * 1000);
        int maxWalkMillimeters = walkSpeedMillimetersPerSecond * (request.maxWalkTime * SECONDS_PER_MINUTE);

        for (int stop = state.nonTransferStopsTouched.nextSetBit(0); stop > -1; stop = state.nonTransferStopsTouched.nextSetBit(stop + 1)) {
            // no need to consider loop transfers, since we don't mark patterns here any more
            // loop transfers are already included by virtue of those stops having been reached
            TIntList transfersFromStop = transit.transfersForStop.get(stop);
            if (transfersFromStop != null) {
                for (int i = 0; i < transfersFromStop.size(); i += 2) {
                    int targetStop = transfersFromStop.get(i);
                    int distanceToTargetStopMillimeters = transfersFromStop.get(i + 1);
                    if (distanceToTargetStopMillimeters < maxWalkMillimeters) {
                        // transfer length to stop is acceptable
                        int walkTimeToTargetStopSeconds = distanceToTargetStopMillimeters / walkSpeedMillimetersPerSecond;
                        int timeAtTargetStop = state.bestNonTransferTimes[stop] + walkTimeToTargetStopSeconds;

                        if (walkTimeToTargetStopSeconds < 0) {
                            LOG.error("Negative transfer time!!");
                        }

                        state.setTimeAtStop(targetStop, timeAtTargetStop, -1, stop, 0, 0, true);
                    }
                }
            }
        }
    }

    /**
     * Get a list of the internal IDs of the patterns "touched" using the given index (frequency or scheduled)
     * "touched" means they were reached in the last round, and the index maps from the original pattern index to the
     * local index of the filtered patterns.
     */
    private BitSet getPatternsTouchedForStops(RaptorState state, int[] index) {
        BitSet patternsTouched = new BitSet();

        for (int stop = state.bestStopsTouched.nextSetBit(0);
             stop >= 0;
             stop = state.bestStopsTouched.nextSetBit(stop + 1)
        ) {
            // copy stop to a new final variable to get around Java 8 "effectively final" nonsense
            final int finalStop = stop;
            transit.patternsForStop.get(stop).forEach(originalPattern -> {
                int filteredPattern = index[originalPattern];

                if (filteredPattern < 0) {
                    return true; // this pattern does not exist in the local subset of patterns, continue iteration
                }

                int sourcePatternIndex = state.previousStop[finalStop] == -1 ?
                        state.previousPatterns[finalStop] :
                        state.previousPatterns[state.previousStop[finalStop]];

                if (sourcePatternIndex != originalPattern) {
                    // don't re-explore the same pattern we used to reach this stop
                    // we forbid riding the same pattern twice in a row in the search code above, this will prevent
                    // us even having to loop over the stops in the pattern if potential board stops were only reached
                    // using this pattern.
                    patternsTouched.set(filteredPattern);
                }
                return true; // continue iteration
            });
        }

        return patternsTouched;
    }
}
