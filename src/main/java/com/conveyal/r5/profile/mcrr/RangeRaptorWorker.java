package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.Path;
import com.conveyal.r5.transit.TripSchedule;
import com.conveyal.r5.util.AvgTimer;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;


/**
 * RaptorWorker is fast, but FastRaptorWorker is knock-your-socks-off fast, and also more maintainable.
 * It is also simpler, as it only focuses on the transit network; see the Propagater class for the methods that extend
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
 * There is currently no support for saving paths.
 *
 * This class originated as a rewrite of our RAPTOR code that would use "thin workers", allowing computation by a
 * generic function-execution service like AWS Lambda. The gains in efficiency were significant enough that this is now
 * the way we do all analysis work. This system also accounts for pure-frequency routes by using Monte Carlo methods
 * (generating randomized schedules).
 */
@SuppressWarnings("Duplicates")
public class RangeRaptorWorker {
    /**
     * Step for departure times. Use caution when changing this as we use the functions
     * request.getTimeWindowLengthMinutes and request.getMonteCarloDrawsPerMinute below which assume this value is 1 minute.
     * The same functions are also used in BootstrappingTravelTimeReducer where we assume that their product is the number
     * of iterations performed.
     */
    private static final int DEPARTURE_STEP_SEC = 60;

    /** Minimum wait for boarding to account for schedule variation */
    private static final int MINIMUM_BOARD_WAIT_SEC = 60;

    private final int nMinutes;
    private final int toTimeSeconds;
    private final int fromTimeSeconds;

    // Variables to track time spent
    private static final AvgTimer TIMER_ROUTE = AvgTimer.timerMilliSec("McRRaptor:route");
    private static final AvgTimer TIMER_ROUTE_SETUP = AvgTimer.timerMilliSec("McRRaptor:route Init");
    private static final AvgTimer TIMER_ROUTE_BY_MINUTE = AvgTimer.timerMilliSec("McRRaptor:route Run Raptor For Minute");
    private static final AvgTimer TIMER_BY_MINUTE_INIT = AvgTimer.timerMilliSec("McRRaptor:runRaptorForMinute Init");
    private static final AvgTimer TIMER_BY_MINUTE_SCHEDULE_SEARCH = AvgTimer.timerMicroSec("McRRaptor:runRaptorForMinute Schedule Search");
    private static final AvgTimer TIMER_BY_MINUTE_TRANSFERS = AvgTimer.timerMicroSec("McRRaptor:runRaptorForMinute Transfers");

    /** the transit data role needed for routing */
    private final RaptorWorkerTransitDataProvider transit;

    /** Times to access each transit stop using the street network (seconds) */
    private final TIntIntMap accessStops;

    /** List of all possible egress stops. */
    private final int[] egressStops;

    private final int walkSpeedMillimetersPerSecond;
    private final int maxWalkMillimeters;

    // TODO add javadoc to field
    private final RangeRaptorWorkerState state;

    /** If we're going to store paths to every destination (e.g. for static sites) then they'll be retained here. */
    public List<Path[]> pathsPerIteration;

    private final PathBuilder pathBuilder;

    public RangeRaptorWorker(
            RaptorWorkerTransitDataProvider transitData,
            RangeRaptorWorkerState state,
            PathBuilder pathBuilder,
            int fromTimeInSeconds,
            int toTimeInSeconds,
            float walkSpeedMPerS,
            int maxWalkTimeMinutes,
            TIntIntMap accessStops,
            int[] egressStops
    ) {
        this.transit = transitData;
        this.accessStops = accessStops;
        this.egressStops = egressStops;

        // Convert to int to avoid integer casts in calculation
        this.walkSpeedMillimetersPerSecond = (int) (walkSpeedMPerS * 1000);
        this.maxWalkMillimeters = walkSpeedMillimetersPerSecond * maxWalkTimeMinutes * 60;

        this.pathBuilder = pathBuilder;
        this.state = state;

        this.toTimeSeconds = toTimeInSeconds;
        this.fromTimeSeconds = fromTimeInSeconds;
        // compute number of minutes for scheduled search
        this.nMinutes = (toTimeInSeconds - fromTimeInSeconds) / 60;
    }

    public int nMinutes() {
        return nMinutes;
    }

    /**
     * For each iteration (minute + MC draw combination), return the minimum travel time to each transit stop in seconds.
     * Return value dimension order is [searchIteration][transitStopIndex]
     */
    public void route () {

        //LOG.info("Performing {} rounds (minutes)",  nMinutes);

        TIMER_ROUTE.time(() -> {
            TIMER_ROUTE_SETUP.start();
            transit.init();
            TIMER_ROUTE_SETUP.stop();


            pathsPerIteration = new ArrayList<>();


            // The main outer loop iterates backward over all minutes in the departure times window.
            for (int departureTime = toTimeSeconds - DEPARTURE_STEP_SEC, minute = nMinutes;
                 departureTime >= fromTimeSeconds;
                 departureTime -= DEPARTURE_STEP_SEC, minute--) {

                int finalDepartureTime = departureTime;

                // Run the raptor search. For this particular departure time, we receive N arrays of arrival times at all
                // stops, one for each randomized schedule: resultsForMinute[randScheduleNumber][transitStop]

                TIMER_ROUTE_BY_MINUTE.time(() ->
                    runRaptorForMinute(finalDepartureTime)
                );
            }
        });
    }

    /**
     * Set the departure time in the scheduled search to the given departure time,
     * and prepare for the scheduled search at the next-earlier minute
     */
    private void advanceScheduledSearchToPreviousMinute (int nextMinuteDepartureTime) {
        state.initNewDepatureForMinute(nextMinuteDepartureTime);

        // add initial stops
        accessStops.forEachEntry((stop, accessTime) -> {
            state.setInitialTime(stop, accessTime + nextMinuteDepartureTime);
            return true; // continue iteration
        });
    }

    /**
     * Perform one minute of a RAPTOR search.
     *
     * @param departureTime When this search departs.
     * @return an array of length iterationsPerMinute, containing the arrival (clock) times at each stop for each iteration.
     */
    private void runRaptorForMinute (int departureTime) {
        McRaptorStateImpl.debugStopHeader("runRaptorForMin "+departureTime);

        TIMER_BY_MINUTE_INIT.time(() ->
                advanceScheduledSearchToPreviousMinute(departureTime)
        );

        // Run the scheduled search
        // round 0 is the street search
        // We are using the Range-RAPTOR extension described in Delling, Daniel, Thomas Pajor, and Renato Werneck.
        // “Round-Based Public Transit Routing,” January 1, 2012. http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
        // ergo, we re-use the arrival times found in searches that have already occurred that depart later, because
        // the arrival time given departure at time t is upper-bounded by the arrival time given departure at minute t + 1.

        while (state.isNewRoundAvailable()) {
            state.gotoNextRound();

            // NB since we have transfer limiting not bothering to cut off search when there are no more transfers
            // as that will be rare and complicates the code grabbing the results

            TIMER_BY_MINUTE_SCHEDULE_SEARCH.time(this::doScheduledSearchForRound);

            TIMER_BY_MINUTE_TRANSFERS.time(this::doTransfers);
        }

        // This state is repeatedly modified as the outer loop progresses over departure minutes.
        // We have to be careful here that creating these paths does not modify the state, and makes
        // protective copies of any information we want to retain.
        pathsPerIteration.add(pathToEachStop());
    }


    /**
     * Create the optimal path to each stop in the transit network, based on the given McRaptorState.
     */
    private Path[] pathToEachStop () {
        int nStops = egressStops.length;
        Path[] paths = new Path[nStops];
        for (int s = 0; s < nStops; s++) {
            int stopIndex = egressStops[s];
            if(state.isStopReachedByTransit(stopIndex)) {
                paths[s] = pathBuilder.extractPathForStop(state.getMaxRound(), stopIndex);
            }
        }
        return paths;
    }

    /** Perform a scheduled search */
    private void doScheduledSearchForRound() {

        BitSet patternsTouched = getPatternsTouchedForStops(transit.getScheduledIndexForOriginalPatternIndex());
        TransitLayerRRDataProvider.PatternIterator patternIterator = transit.patternIterator(patternsTouched);


        while(patternIterator.morePatterns()) {
            TransitLayerRRDataProvider.Pattern pattern = patternIterator.next();
            int originalPatternIndex = pattern.originalPatternIndex();
            int onTrip = -1;
            int boardTime = 0;
            int boardStop = -1;
            TripSchedule schedule = null;

            for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.currentPatternStopsSize(); stopPositionInPattern++) {
                int stop = pattern.currentPatternStop(stopPositionInPattern);

                // attempt to alight if we're on board, done above the board search so that we don't check for alighting
                // when boarding
                if (onTrip > -1) {
                    int alightTime = schedule.arrivals[stopPositionInPattern];

                    state.transitToStop(
                            stop,
                            alightTime,
                            originalPatternIndex,
                            pattern.getTripSchedulesIndex(schedule),
                            boardStop,
                            boardTime
                    );
                }

                int sourcePatternIndex = state.getPatternIndexForPreviousRound(stop);

                // Don't attempt to board if this stop was not reached in the last round, and don't attempt to
                // reboard the same pattern
                if (state.isStopReachedInLastRound(stop) && sourcePatternIndex != originalPatternIndex) {
                    int earliestBoardTime = state.bestTimePreviousRound(stop) + MINIMUM_BOARD_WAIT_SEC;

                    // only attempt to board if the stop was touched
                    if (onTrip == -1) {
                        int candidateTripIndex = -1;
                        EARLIEST_TRIP:
                        for (TripSchedule candidateSchedule : pattern.getTripSchedules()) {
                            candidateTripIndex++;

                            if (transit.skipCalendarService(candidateSchedule.serviceCode) || candidateSchedule.headwaySeconds != null) {
                                // frequency trip or not running
                                continue;
                            }

                            if (earliestBoardTime < candidateSchedule.departures[stopPositionInPattern]) {
                                // board this vehicle
                                onTrip = candidateTripIndex;
                                schedule = candidateSchedule;
                                boardTime = candidateSchedule.departures[stopPositionInPattern];
                                boardStop = stop;
                                break EARLIEST_TRIP;
                            }
                        }
                    } else {
                        // check if we can back up to an earlier trip due to this stop being reached earlier
                        int bestTripIdx = onTrip;
                        while (--bestTripIdx >= 0) {
                            TripSchedule trip = pattern.getTripSchedule(bestTripIdx);
                            if (trip.headwaySeconds != null || transit.skipCalendarService(trip.serviceCode)) {
                                // This is a frequency trip or it is not running on the day of the search.
                                continue;
                            }
                            if (trip.departures[stopPositionInPattern] > earliestBoardTime) {
                                onTrip = bestTripIdx;
                                schedule = trip;
                                boardTime = trip.departures[stopPositionInPattern];
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

    private void doTransfers () {

        BitSetIterator it = state.stopsTouchedByTransitCurrentRoundIterator();

        for (int stop = it.next(); stop > -1; stop = it.next()) {
            // no need to consider loop transfers, since we don't mark patterns here any more
            // loop transfers are already included by virtue of those stops having been reached
            TIntList transfersFromStop = transit.getTransfersDistancesInMMForStop(stop);

            if (transfersFromStop != null) {
                for (int stopIdx = 0; stopIdx < transfersFromStop.size(); stopIdx += 2) {
                    int targetStop = transfersFromStop.get(stopIdx);
                    int distanceToTargetStopMillimeters = transfersFromStop.get(stopIdx + 1);

                    if (distanceToTargetStopMillimeters < maxWalkMillimeters) {
                        // transfer length to stop is acceptable
                        int walkTimeToTargetStopSeconds = distanceToTargetStopMillimeters / walkSpeedMillimetersPerSecond;
                        int timeAtTargetStop = state.bestTransitTime(stop) + walkTimeToTargetStopSeconds;

                        if (walkTimeToTargetStopSeconds < 0) {
                            throw new IllegalStateException("Negative transfer time!!");
                        }

                        state.transferToStop(targetStop, timeAtTargetStop, stop, walkTimeToTargetStopSeconds);
                    }
                }
            }
        }
    }

    /**
     * Get a list of the internal IDs of the patterns "touched" using the given index (frequency or scheduled)
     * "touched" means they were reached in the last round, and the index maps from the original pattern index to the
     * local index of the filtered patterns.
     *
     * TODO TGR - The responsibility of this method overlap with the transit data provider, but it is
     * TODO TGR - tied to the store, so I leave it for now. Task: Pull it appart and push to data provider.
     */
    private BitSet getPatternsTouchedForStops(int[] index) {

        BitSet patternsTouched = new BitSet();
        BitSetIterator it = state.bestStopsTouchedLastRoundIterator();

        for (int stop = it.next(); stop >= 0; stop = it.next()) {
            // copy stop to a new final variable to get around Java 8 "effectively final" nonsense
            final int finalStop = stop;
            transit.getPatternsForStop(stop).forEach(originalPattern -> {
                int filteredPattern = index[originalPattern];

                if (filteredPattern < 0) {
                    return true; // this pattern does not exist in the local subset of patterns, continue iteration
                }

                int sourcePatternIndex = state.getPatternIndexForPreviousRound(finalStop);

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
