package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.api.TripPatternInfo;
import com.conveyal.r5.profile.entur.rangeraptor.TripScheduleBoardSearch;
import com.conveyal.r5.profile.entur.api.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.TransitDataProvider;
import com.conveyal.r5.profile.entur.rangeraptor.AbstractRangeRaptorWorker;
import com.conveyal.r5.profile.entur.util.AvgTimer;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

import java.util.Collection;
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
public class RangeRaptorWorker<T extends TripScheduleInfo> extends AbstractRangeRaptorWorker<RangeRaptorWorkerState<T>, T> {

    // Variables to track time spent
    private static final AvgTimer TIMER_ROUTE = AvgTimer.timerMilliSec("RRaptor:route");
    private static final AvgTimer TIMER_ROUTE_SETUP =  AvgTimer.timerMilliSec("RRaptor:route Init");
    private static final AvgTimer TIMER_ROUTE_BY_MINUTE = AvgTimer.timerMilliSec("RRaptor:route Run Raptor For Minute");
    private static final AvgTimer TIMER_BY_MINUTE_SCHEDULE_SEARCH = AvgTimer.timerMicroSec("RRaptor:runRaptorForMinute Schedule Search");
    private static final AvgTimer TIMER_BY_MINUTE_TRANSFERS = AvgTimer.timerMicroSec("RRaptor:runRaptorForMinute Transfers");

    public RangeRaptorWorker(
            TransitDataProvider<T> transitData,
            int nRounds,
            RangeRaptorRequest request
    ) {
        super(
                transitData,
                new RangeRaptorWorkerState<>(nRounds, transitData.numberOfStops(), request),
                request
        );
    }

    @Override
    protected Collection<Path2<T>> paths() {
        return state.paths();
    }

    /**
     * Create the optimal path to each stop in the transit network, based on the given McRaptorState.
     */
    @Override
    protected void addPathsForCurrentIteration() {
        state.addPathsForCurrentIteration();
    }

    /**
     * Perform a scheduled search
     */
    @Override
    protected void scheduledSearchForRound() {

        Iterator<? extends TripPatternInfo<T>> patternIterator = transit.patternIterator(state.bestStopsTouchedLastRoundIterator());

        while (patternIterator.hasNext()) {
            TripPatternInfo<T> pattern = patternIterator.next();
            int onTrip = -1;
            int boardTime = 0;
            int boardStop = -1;
            T boardTrip = null;

            TripScheduleBoardSearch<T> search = new TripScheduleBoardSearch<>(pattern, this::skipTripSchedule);

            for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.numberOfStopsInPattern(); stopPositionInPattern++) {
                int stop = pattern.currentPatternStop(stopPositionInPattern);

                // attempt to alight if we're on board, done above the board search so that we don't check for alighting
                // when boarding
                if (onTrip != -1) {
                    state.transitToStop(
                            stop,
                            boardTrip.arrival(stopPositionInPattern),
                            boardTrip,
                            boardStop,
                            boardTime
                    );
                }

                // Don't attempt to board if this stop was not reached in the last round.
                // Allow to reboard the same pattern - a pattern may loop and visit the same stop twice
                if (state.isStopReachedInPreviousRound(stop)) {
                    int earliestBoardTime = earliestBoardTime(state.bestTimePreviousRound(stop));
                    int tripIndexUpperBound = (onTrip == -1 ? pattern.numberOfTripSchedules() : onTrip);

                    // check if we can back up to an earlier trip due to this stop being reached earlier
                    boolean found = search.search(tripIndexUpperBound, earliestBoardTime, stopPositionInPattern);

                    if (found) {
                        onTrip = search.candidateTripIndex;
                        boardTrip = search.candidateTrip;
                        boardTime = search.candidateTrip.departure(stopPositionInPattern);
                        boardStop = stop;
                    }
                }
            }
        }
    }

    @Override protected AvgTimer timerRoute() { return TIMER_ROUTE; }
    @Override protected void timerSetup(Runnable setup) { TIMER_ROUTE_SETUP.time(setup); }
    @Override protected void timerRouteByMinute(Runnable routeByMinute) { TIMER_ROUTE_BY_MINUTE.time(routeByMinute); }
    @Override protected AvgTimer timerByMinuteScheduleSearch(){ return TIMER_BY_MINUTE_SCHEDULE_SEARCH; }
    @Override protected AvgTimer timerByMinuteTransfers(){ return TIMER_BY_MINUTE_TRANSFERS; }
}
