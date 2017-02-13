package com.conveyal.r5.profile;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * RaptorWorker is fast, but FastRaptorWorker is knock-your-socks-off fast, and also more maintainable.
 * It is also simpler; all propagation code has been removed and will be performed elsewhere if desired.
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
 * Fast is a bit of a misnomer; it's currently slower than RaptorWorker. But I think that's mostly due to a naïeve
 * implementation of doScheduledRound. It is cleaner and more maintainable.
 *
 * There is currently no support for saving paths.
 */
public class FastRaptorWorker {
    private static final Logger LOG = LoggerFactory.getLogger(FastRaptorWorker.class);

    /** Step for departure times */
    private static final int DEPARTURE_STEP_SEC = 60;

    /** Minimum wait for boarding to account for schedule variation */
    private static final int MINIMUM_BOARD_WAIT_SEC = 60;

    // Variables to track time spent
    public long startClockTime;

    /** the transit layer to route on */
    private final TransitLayer transit;

    /** Times to access each transit stop using the street network (seconds) */
    private final TIntIntMap accessStops;

    /** The profilerequest describing routing parameters */
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

    private final RaptorState[] scheduleState;

    public FastRaptorWorker (TransitLayer transitLayer, ProfileRequest request, TIntIntMap accessStops) {
        this.transit = transitLayer;
        this.request = request;
        this.accessStops = accessStops;
        this.servicesActive  = transit.getActiveServicesForDate(request.date);
        // we add one to request.maxRides, first state is result of initial walk
        this.scheduleState = IntStream.range(0, request.maxRides + 1)
                .mapToObj((i) -> new RaptorState(transit.getStopCount())).toArray(RaptorState[]::new);
        offsets = new FrequencyRandomOffsets(transitLayer);
    }

    /** For each iteration, return the travel time to each transit stop */
    public int[][] route () {
        startClockTime = System.currentTimeMillis();
        prefilterPatterns();

        // compute number of minutes for scheduled search
        int nMinutes = (request.toTime - request.fromTime) / DEPARTURE_STEP_SEC;

        // how many monte carlo draws per minute of scheduled search to get desired total iterations?
        int monteCarloDrawsPerMinute = (int) Math.ceil((double) request.monteCarloDraws / nMinutes);

        LOG.info("Performing {} scheduled iterations each with {} Monte Carlo draws for a total of {} iterations",
                nMinutes, monteCarloDrawsPerMinute, nMinutes * monteCarloDrawsPerMinute);

        int[][] results = new int[transit.hasFrequencies ? nMinutes * monteCarloDrawsPerMinute : nMinutes][];
        int currentIteration = 0;

        // main loop over departure times
        for (int departureTime = request.toTime - DEPARTURE_STEP_SEC, minute = nMinutes;
             departureTime >= request.fromTime; departureTime -= DEPARTURE_STEP_SEC, minute--) {
            if (minute % 15 == 0) LOG.info("  minute {}", minute);

            updateDepartureTime(departureTime);

            // run the search
            int[][] resultsForMinute = runRaptorForMinute(monteCarloDrawsPerMinute);

            // effectively final nonsense
            final int finalDepartureTime = departureTime;
            for (int[] resultsForIteration : resultsForMinute) {
                results[currentIteration++] = IntStream.of(resultsForIteration).map(r -> r - finalDepartureTime).toArray();
            }
        }

        LOG.info("Search completed in {}s", (System.currentTimeMillis() - startClockTime) / 1000d);

        return results;
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
            if (pattern.servicesActive.intersects(servicesActive)) {
                // at least one trip on this pattern is relevant
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

    /** Set the departure time in the scheduled search to the given departure time */
    private void updateDepartureTime (int departureTime) {
        for (RaptorState state : this.scheduleState) {
            state.setDepartureTime(departureTime);
            // TODO prune trips that are now longer than max lengths to avoid biasing averages
        }


        // add initial stops
        RaptorState initialState = scheduleState[0];
        accessStops.forEachEntry((stop, accessTime) -> {
            initialState.setTimeAtStop(stop, accessTime + departureTime, true);
            return true; // continue iteration
        });
    }

    /** Perform one minute of a RAPTOR search */
    private int[][] runRaptorForMinute(int iterationsPerMinute) {
        // Run the scheduled search
        // round 0 is the street search
        if (transit.hasSchedules) {
            for (int round = 1; round <= request.maxRides; round++) {
                // NB since we have transfer limiting not bothering to cut off search when there are no more transfers
                // as that will be rare and complicates the code grabbing the results

                // prevent finding crazy multi-transfer ways to get somewhere when there is a quicker way with fewer
                // transfers
                scheduleState[round].min(scheduleState[round - 1]);
                doScheduledSearchForRound(scheduleState[round - 1], scheduleState[round]);
                doTransfers(scheduleState[round]);
            }
        }

        if (transit.hasFrequencies) {
            int[][] result = new int[iterationsPerMinute][];
            for (int iteration = 0; iteration < iterationsPerMinute; iteration++) {
                // copy the state, with advancingRound = false
                RaptorState[] frequencyState = Stream.of(scheduleState).map((s) -> s.copy()).toArray(RaptorState[]::new);

                // take a new Monte Carlo draw
                // Einstein was probably wrong; God does in fact play dice with the universe, and so do we
                offsets.randomize();

                // re-mark all access stops
                accessStops.forEachEntry((stop, time) -> {
                    frequencyState[0].bestStopsTouched.set(stop);
                    return true;
                });

                for (int round = 1; round <= request.maxRides; round++) {
                    frequencyState[round].min(frequencyState[round - 1]);

                    // scheduled search: use only stops touched within this loop
                    // we need to repeat the scheduled search when we do frequency searches to handle combinations of schedules
                    // and frequencies
                    doScheduledSearchForRound(frequencyState[round - 1], frequencyState[round]);

                    // frequency search: additionally use stops touched by scheduled search
                    // okay to destructively modify last round frequency state, it will not be used after this
                    frequencyState[round - 1].bestStopsTouched.or(scheduleState[round - 1].bestStopsTouched);
                    frequencyState[round - 1].nonTransferStopsTouched.or(scheduleState[round - 1].nonTransferStopsTouched);
                    doFrequencySearchForRound(frequencyState[round - 1], frequencyState[round]);

                    doTransfers(frequencyState[round]);
                }

                result[iteration] = frequencyState[request.maxRides].bestNonTransferTimes;
            }
            return result;
        } else {
            // no frequencies, return result of scheduled search
            return new int[][] { scheduleState[request.maxRides].bestNonTransferTimes };
        }
    }

    /** Perform a scheduled search */
    private void doScheduledSearchForRound(RaptorState inputState, RaptorState outputState) {
        BitSet patternsTouched = getPatternsTouchedForStops(inputState.bestStopsTouched, scheduledIndexForOriginalPatternIndex);

        for (int patternIndex = patternsTouched.nextSetBit(0); patternIndex >= 0; patternIndex = patternsTouched.nextSetBit(patternIndex + 1)) {
            TripPattern pattern = runningScheduledPatterns[patternIndex];
            // TODO do we need to loop over every schedule here? Probably not. The existing RaptorWorker does not.
            for (TripSchedule schedule : pattern.tripSchedules) {
                // frequency trip or not running
                if (!servicesActive.get(schedule.serviceCode) || schedule.headwaySeconds != null) continue;

                boolean onTrip = false; // will be set to true once we board

                for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.stops.length; stopPositionInPattern++) {
                    int stop = pattern.stops[stopPositionInPattern];

                    // attempt to alight if we're on board, done above the board search so that we don't check for alighting
                    // when boarding
                    if (onTrip) {
                        outputState.setTimeAtStop(stop, schedule.arrivals[stopPositionInPattern], false);
                    } else {
                        // no need to check for boarding if we're already on this (scheduled) trip, it won't improve any
                        // times down the line. This means that we board each trip at the earliest possible stop.
                        // we may want to implement some transfer filtering to make this more sane if the transferfinder
                        // doesn't already take care of that for us.

                        // only attempt to board if the stop was touched
                        if (inputState.bestStopsTouched.get(stop)) {
                            if (inputState.bestTimes[stop] + MINIMUM_BOARD_WAIT_SEC < schedule.departures[stopPositionInPattern]) {
                                // board this vehicle
                                onTrip = true;
                            }
                        }
                    }
                }
            }
        }
    }

    private void doFrequencySearchForRound(RaptorState inputState, RaptorState outputState) {
        BitSet patternsTouched = getPatternsTouchedForStops(inputState.bestStopsTouched, frequencyIndexForOriginalPatternIndex);

        for (int patternIndex = patternsTouched.nextSetBit(0); patternIndex >= 0; patternIndex = patternsTouched.nextSetBit(patternIndex + 1)) {
            TripPattern pattern = runningFrequencyPatterns[patternIndex];

            int tripScheduleIndex = -1; // first increment lands at 0
            for (TripSchedule schedule : pattern.tripSchedules) {
                tripScheduleIndex++;

                // scheduled trip or not running
                if (!servicesActive.get(schedule.serviceCode) || schedule.headwaySeconds == null) continue;

                for (int frequencyEntryIdx = 0; frequencyEntryIdx < schedule.headwaySeconds.length; frequencyEntryIdx++) {
                    int originalPatternIndex = originalPatternIndexForFrequencyIndex[patternIndex];
                    int offset = offsets.offsets.get(originalPatternIndex)[tripScheduleIndex][frequencyEntryIdx];

                    int boardTime = -1;
                    int boardStopPositionInPattern = -1;

                    for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.stops.length; stopPositionInPattern++) {
                        int stop = pattern.stops[stopPositionInPattern];

                        // attempt to alight if boarded
                        if (boardTime > -1) {
                            // attempt to alight
                            int travelTime = schedule.arrivals[stopPositionInPattern] - schedule.departures[boardStopPositionInPattern];
                            int alightTime = boardTime + travelTime;
                            outputState.setTimeAtStop(stop, alightTime, false);
                        }

                        // attempt to board (even if already boarded, since this is a frequency trip and we could move back)
                        if (inputState.bestStopsTouched.get(stop)) {
                            int newBoardingDepartureTimeAtStop =
                                    getFrequencyDepartureTime(schedule, stopPositionInPattern, offset, frequencyEntryIdx, inputState.bestTimes[stop] + MINIMUM_BOARD_WAIT_SEC);

                            int remainOnBoardDepartureTimeAtStop = Integer.MAX_VALUE;

                            if (boardTime > -1) {
                                // Cannot re-use calc from above, we're using departure time at this stop here to account for
                                // any dwell time (TODO this may be done incorrectly in existing RaptorWorker)
                                int travelTime = schedule.departures[stopPositionInPattern] - schedule.departures[boardStopPositionInPattern];
                                remainOnBoardDepartureTimeAtStop = boardTime + travelTime;
                            }
                            if (newBoardingDepartureTimeAtStop > -1 && newBoardingDepartureTimeAtStop < remainOnBoardDepartureTimeAtStop) {
                                // board this trip
                                boardTime = newBoardingDepartureTimeAtStop;
                                boardStopPositionInPattern = stopPositionInPattern;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Get the earliest departure time on a particular scheduled frequency entry, or -1 if the frequency entry is not usable */
    public int getFrequencyDepartureTime (TripSchedule schedule, int stopPositionInPattern, int offset, int frequencyEntryIdx, int earliestTime) {
        // earliest board time is start time plus travel time plus offset
        int earliestBoardTimeThisEntry = schedule.startTimes[frequencyEntryIdx] +
                schedule.departures[stopPositionInPattern] +
                offset;

        // compute the number of trips on this entry
        // We take the difference between the end time and the start time including the offset
        // to get the time between the first trip and the last possible trip. We int-divide by the
        // headway and add one to correct for the fencepost problem.
        int numberOfTripsThisEntry = (schedule.endTimes[frequencyEntryIdx] - (schedule.startTimes[frequencyEntryIdx] + offset)) /
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

    private void doTransfers (RaptorState state) {
        // avoid integer casts in tight loop below
        int walkSpeedMillimetersPerSecond = (int) (request.walkSpeed * 1000);
        int maxWalkMillimeters = (int) (request.walkSpeed * request.maxWalkTime * 60 * 1000);

        for (int stop = state.nonTransferStopsTouched.nextSetBit(0); stop > -1; stop = state.nonTransferStopsTouched.nextSetBit(stop + 1)) {
            // no need to consider loop transfers, since we don't mark patterns here any more
            // loop transfers are already included by virtue of those stops having been reached
            TIntList transfersFromStop = transit.transfersForStop.get(stop);
            if (transfersFromStop != null) {
                for (TIntIterator it = transfersFromStop.iterator(); it.hasNext(); ) {
                    int targetStop = it.next();
                    int distanceToTargetStopMillimeters = it.next();

                    if (distanceToTargetStopMillimeters < maxWalkMillimeters) {
                        // transfer length to stop is acceptable
                        int walkTimeToTargetStopSeconds = distanceToTargetStopMillimeters / walkSpeedMillimetersPerSecond;
                        int timeAtTargetStop = state.bestNonTransferTimes[stop] + walkTimeToTargetStopSeconds;
                        state.setTimeAtStop(targetStop, timeAtTargetStop, true);
                    }
                }
            }
        }
    }

    /** Get a list of the internal IDs of the patterns touched using the given index (frequency or scheduled) */
    private BitSet getPatternsTouchedForStops(BitSet stopsTouched, int[] index) {
        BitSet patternsTouched = new BitSet();

        for (int stop = stopsTouched.nextSetBit(0); stop >= 0; stop = stopsTouched.nextSetBit(stop + 1)) {
            transit.patternsForStop.get(stop).forEach(pattern -> {
                int originalPattern = index[pattern];
                if (originalPattern > -1) patternsTouched.set(originalPattern);
                return true; // continue iteration
            });
        }

        return patternsTouched;
    }
}
