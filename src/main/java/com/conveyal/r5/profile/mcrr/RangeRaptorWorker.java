package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.mcrr.api.Pattern;
import com.conveyal.r5.profile.mcrr.api.TimeToStop;
import com.conveyal.r5.profile.mcrr.api.TransitDataProvider;
import com.conveyal.r5.profile.mcrr.api.Worker;
import com.conveyal.r5.profile.mcrr.util.AvgTimer;
import com.conveyal.r5.profile.mcrr.util.TimeUtils;
import com.conveyal.r5.transit.TripSchedule;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;


/**
 * RaptorWorker is fast, but FastRaptorWorker is knock-your-socks-off fast, and also more maintainable.
 * It is also simpler, as it only focuses on the transit network; see the Propagater class for the methods that extend
 * the travel times from the final transit stop of a trip out to the individual targets.
 * <p>
 * The algorithm used herein is described in
 * <p>
 * Conway, Matthew Wigginton, Andrew Byrd, and Marco van der Linden. “Evidence-Based Transit and Land Use Sketch Planning
 * Using Interactive Accessibility Methods on Combined Schedule and Headway-Based Networks.” Transportation Research
 * Record 2653 (2017). doi:10.3141/2653-06.
 * <p>
 * Delling, Daniel, Thomas Pajor, and Renato Werneck. “Round-Based Public Transit Routing,” January 1, 2012.
 * http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
 * <p>
 * There is currently no support for saving paths.
 * <p>
 * This class originated as a rewrite of our RAPTOR code that would use "thin workers", allowing computation by a
 * generic function-execution service like AWS Lambda. The gains in efficiency were significant enough that this is now
 * the way we do all analysis work. This system also accounts for pure-frequency routes by using Monte Carlo methods
 * (generating randomized schedules).
 */
@SuppressWarnings("Duplicates")
public class RangeRaptorWorker implements Worker {
    /**
     * Step for departure times. Use caution when changing this as we use the functions
     * request.getTimeWindowLengthMinutes and request.getMonteCarloDrawsPerMinute below which assume this value is 1 minute.
     * The same functions are also used in BootstrappingTravelTimeReducer where we assume that their product is the number
     * of iterations performed.
     */
    private static final int DEPARTURE_STEP_SEC = 60;

    /**
     * Minimum wait for boarding to account for schedule variation
     */
    private static final int MINIMUM_BOARD_WAIT_SEC = 60;

    private final int nMinutes;
    private final int toTimeSeconds;
    private final int fromTimeSeconds;

    // Variables to track time spent
    private static final AvgTimer TIMER_ROUTE = AvgTimer.timerMilliSec("RRaptor:route");
    private static final AvgTimer TIMER_ROUTE_SETUP = AvgTimer.timerMilliSec("RRaptor:route Init");
    private static final AvgTimer TIMER_ROUTE_BY_MINUTE = AvgTimer.timerMilliSec("RRaptor:route Run Raptor For Minute");
    private static final AvgTimer TIMER_BY_MINUTE_SCHEDULE_SEARCH = AvgTimer.timerMicroSec("RRaptor:runRaptorForMinute Schedule Search");
    private static final AvgTimer TIMER_BY_MINUTE_TRANSFERS = AvgTimer.timerMicroSec("RRaptor:runRaptorForMinute Transfers");
    private static final AvgTimer TIMER_TRIP = AvgTimer.timerMicroSec("TRIP");

    /** the transit data role needed for routing */
    private final TransitDataProvider transit;

    /** Times to access each transit stop using the street network (seconds) */
    private final Collection<TimeToStop> accessStops;

    /** List of all possible egress stops. */
    private final Collection<TimeToStop> egressStops;

    // TODO add javadoc to field
    private final RangeRaptorWorkerState state;

    /** If we're going to store paths to every destination (e.g. for static sites) then they'll be retained here. */
    public Collection<Path> paths;

    private final PathBuilder pathBuilder;

    public RangeRaptorWorker(
            TransitDataProvider transitData,
            RangeRaptorWorkerState state,
            PathBuilder pathBuilder,
            int fromTimeInSeconds,
            int toTimeInSeconds,
            Collection<TimeToStop> accessStops,
            Collection<TimeToStop> egressStops
    ) {
        this.transit = transitData;
        this.accessStops = accessStops;
        this.egressStops = egressStops;

        this.pathBuilder = pathBuilder;
        this.state = state;

        this.toTimeSeconds = toTimeInSeconds;
        this.fromTimeSeconds = fromTimeInSeconds;
        // compute number of minutes for scheduled search
        this.nMinutes = (toTimeInSeconds - fromTimeInSeconds) / 60;
        this.paths = new HashSet<>();
    }

    /**
     * For each iteration (minute + MC draw combination), return the minimum travel time to each transit stop in seconds.
     * Return value dimension order is [searchIteration][transitStopIndex]
     *
     * @return a unique set of paths
     */
    public Collection<Path> route() {

        //LOG.info("Performing {} rounds (minutes)",  nMinutes);

        TIMER_ROUTE.time(() -> {
            TIMER_ROUTE_SETUP.start();
            transit.init();
            TIMER_ROUTE_SETUP.stop();

            // The main outer loop iterates backward over all minutes in the departure times window.
            for (int departureTime = toTimeSeconds - DEPARTURE_STEP_SEC;
                 departureTime >= fromTimeSeconds;
                 departureTime -= DEPARTURE_STEP_SEC) {

                int finalDepartureTime = departureTime;

                // Run the raptor search. For this particular departure time, we receive N arrays of arrival times at all
                // stops, one for each randomized schedule: resultsForMinute[randScheduleNumber][transitStop]

                TIMER_ROUTE_BY_MINUTE.time(() ->
                    runRaptorForMinute(finalDepartureTime)
                );
            }
        });
        return paths;
    }

    /**
     * Set the departure time in the scheduled search to the given departure time,
     * and prepare for the scheduled search at the next-earlier minute
     */
    private void advanceScheduledSearchToPreviousMinute(int nextMinuteDepartureTime) {
        state.initNewDepatureForMinute(nextMinuteDepartureTime);

        // add initial stops
        for (TimeToStop it : accessStops) {
            state.setInitialTime(it.stop, it.time + nextMinuteDepartureTime);
        }
    }

    /**
     * Perform one minute of a RAPTOR search.
     *
     * @param departureTime When this search departs.
     * @return an array of length iterationsPerMinute, containing the arrival (clock) times at each stop for each iteration.
     */
    private void runRaptorForMinute(int departureTime) {
        RangeRaptorWorkerState.debugStopHeader("RUN RAPTOR FOR MINUTE: " + TimeUtils.timeToStrCompact(departureTime));

        advanceScheduledSearchToPreviousMinute(departureTime);

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
            TIMER_BY_MINUTE_SCHEDULE_SEARCH.time(this::scheduledSearchForRound);

            TIMER_BY_MINUTE_TRANSFERS.time(this::doTransfers);
        }

        // This state is repeatedly modified as the outer loop progresses over departure minutes.
        // We have to be careful here that creating these paths does not modify the state, and makes
        // protective copies of any information we want to retain.
        addPathsForCurrentIteration();
    }


    /**
     * Create the optimal path to each stop in the transit network, based on the given McRaptorState.
     */
    private void addPathsForCurrentIteration() {
        for (TimeToStop it : egressStops) {

            // TODO TGR -- Add egress transit time to path

            if (state.isStopReachedByTransit(it.stop)) {
                Path p = pathBuilder.extractPathForStop(state.getMaxNumberOfRounds(), it.stop);
                if (p != null) {
                    paths.add(p);
                }
            }
        }
    }

    /** Perform a scheduled search */
    private void scheduledSearchForRound() {

        Iterator<Pattern> patternIterator = transit.patternIterator(state.bestStopsTouchedLastRoundIterator());

        while (patternIterator.hasNext()) {
            Pattern pattern = patternIterator.next();
            int originalPatternIndex = pattern.originalPatternIndex();
            int onTrip = -1;
            int boardTime = 0;
            int boardStop = -1;
            TripSchedule boardTrip = null;

            TripScheduleBoardSearch search = new TripScheduleBoardSearch(pattern, this::skipTripSchedule);

            for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.currentPatternStopsSize(); stopPositionInPattern++) {
                int stop = pattern.currentPatternStop(stopPositionInPattern);

                // attempt to alight if we're on board, done above the board search so that we don't check for alighting
                // when boarding
                if (onTrip != -1) {
                    state.transitToStop(
                            stop,
                            boardTrip.arrivals[stopPositionInPattern],
                            originalPatternIndex,
                            pattern.getTripSchedulesIndex(boardTrip),
                            boardStop,
                            boardTime
                    );
                }

                // Don't attempt to board if this stop was not reached in the last round.
                // Allow to reboard the same pattern - a pattern may loop and visit the same stop twice
                if (state.isStopReachedInPreviousRound(stop)) {
                    int earliestBoardTime = state.bestTimePreviousRound(stop) + MINIMUM_BOARD_WAIT_SEC;
                    int tripIndexUpperBound = (onTrip == -1 ? pattern.getTripScheduleSize() : onTrip);

                    // check if we can back up to an earlier trip due to this stop being reached earlier
                    TIMER_TRIP.start();
                    boolean found = search.search(tripIndexUpperBound, earliestBoardTime, stopPositionInPattern);
                    TIMER_TRIP.stop();

                    if (found) {
                        onTrip = search.candidateTripIndex;
                        boardTrip = search.candidateTrip;
                        boardTime = search.candidateTrip.departures[stopPositionInPattern];
                        boardStop = stop;
                    }
                }
            }
        }
    }

    private void doTransfers() {
        BitSetIterator it = state.stopsTouchedByTransitCurrentRoundIterator();
        for (int fromStop = it.next(); fromStop > -1; fromStop = it.next()) {
            // no need to consider loop transfers, since we don't mark patterns here any more
            // loop transfers are already included by virtue of those stops having been reached
            for (TimeToStop transfer : transit.getTransfers(fromStop)) {
                state.transferToStop(fromStop, transfer.stop, transfer.time);
            }
        }
    }

    /** Skip trips NOT running on the day of the search and skip frequency trips */
    private boolean skipTripSchedule(TripSchedule trip) {
        return trip.headwaySeconds != null || transit.skipCalendarService(trip.serviceCode);
    }
}
