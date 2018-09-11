package com.conveyal.r5.profile.mcrr.mc;

import com.conveyal.r5.profile.mcrr.AbstractRangeRaptorWorker;
import com.conveyal.r5.profile.mcrr.BitSetIterator;
import com.conveyal.r5.profile.mcrr.api.Path2;
import com.conveyal.r5.profile.mcrr.api.Pattern;
import com.conveyal.r5.profile.mcrr.api.DurationToStop;
import com.conveyal.r5.profile.mcrr.api.RangeRaptorRequest;
import com.conveyal.r5.profile.mcrr.api.TransitDataProvider;
import com.conveyal.r5.profile.mcrr.TripScheduleBoardSearch;
import com.conveyal.r5.profile.mcrr.util.AvgTimer;
import com.conveyal.r5.transit.TripSchedule;

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
public class McRangeRaptorWorker extends AbstractRangeRaptorWorker<McWorkerState, Path2> {

    // Variables to track time spent
    private static final AvgTimer TIMER_ROUTE = AvgTimer.timerMilliSec("McRR:route");
    private static final AvgTimer TIMER_ROUTE_SETUP = AvgTimer.timerMilliSec("McRR:route Init");
    private static final AvgTimer TIMER_ROUTE_BY_MINUTE = AvgTimer.timerMilliSec("McRR:route Run Raptor For Minute");
    private static final AvgTimer TIMER_BY_MINUTE_SCHEDULE_SEARCH = AvgTimer.timerMicroSec("McRR:runRaptorForMinute Schedule Search");
    private static final AvgTimer TIMER_BY_MINUTE_TRANSFERS = AvgTimer.timerMicroSec("McRR:runRaptorForMinute Transfers");


    public McRangeRaptorWorker(TransitDataProvider transitData, McWorkerState state) {
        super(transitData, state);
    }

    @Override protected Collection<Path2> paths(Collection<DurationToStop> egressStops) {
        return state.extractPaths(egressStops);
    }


    @Override protected void addPathsForCurrentIteration(Collection<DurationToStop> egressStops) {
        // NOOP
    }

    /**
     * Perform a scheduled search
     * @param boardSlackInSeconds {@link RangeRaptorRequest#boardSlackInSeconds}
     */
    @Override protected void scheduledSearchForRound(final int boardSlackInSeconds) {
        BitSetIterator stops = state.stopsTouchedPreviousRound();
        Iterator<Pattern> patternIterator = transit.patternIterator(stops);

        while (patternIterator.hasNext()) {
            Pattern pattern = patternIterator.next();
            int originalPatternIndex = pattern.originalPatternIndex();

            TripScheduleBoardSearch search = new TripScheduleBoardSearch(pattern, this::skipTripSchedule);

            for (int boardStopPosInPtn = 0; boardStopPosInPtn < pattern.currentPatternStopsSize(); boardStopPosInPtn++) {
                int boardStopIndex = pattern.currentPatternStop(boardStopPosInPtn);

                for (McStopState boardStop : state.listStopStatesPreviousRound(boardStopIndex)) {

                    int earliestBoardTime = boardStop.time() + boardSlackInSeconds;
                    boolean found = search.search(earliestBoardTime, boardStopPosInPtn);

                    for (int alightStopPosInPtn = boardStopPosInPtn + 1; alightStopPosInPtn < pattern.currentPatternStopsSize(); alightStopPosInPtn++) {
                        int alightStopIndex = pattern.currentPatternStop(alightStopPosInPtn);

                        if (found) {
                            TripSchedule trip = search.candidateTrip;
                            state.transitToStop(
                                    boardStop,
                                    alightStopIndex,
                                    trip.arrivals[alightStopPosInPtn],
                                    originalPatternIndex,
                                    search.candidateTripIndex,
                                    trip.departures[boardStopPosInPtn]
                            );
                        }
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
