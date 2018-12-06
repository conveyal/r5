package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.api.transit.UnsignedIntIterator;
import com.conveyal.r5.profile.entur.rangeraptor.AbstractRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleBoardSearch;
import com.conveyal.r5.profile.entur.util.AvgTimer;

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
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
@SuppressWarnings("Duplicates")
public class McRangeRaptorWorker<T extends TripScheduleInfo> extends AbstractRangeRaptorWorker<McRangeRaptorWorkerState<T>, T> {

    // Variables to track time spent
    private static final AvgTimer TIMER_ROUTE = AvgTimer.timerMilliSec("McRR:route");
    private static final AvgTimer TIMER_ROUTE_SETUP = AvgTimer.timerMilliSec("McRR:route Init");
    private static final AvgTimer TIMER_ROUTE_BY_MINUTE = AvgTimer.timerMilliSec("McRR:route Run Raptor For Minute");
    private static final AvgTimer TIMER_BY_MINUTE_SCHEDULE_SEARCH = AvgTimer.timerMicroSec("McRR:runRaptorForMinute Schedule Search");
    private static final AvgTimer TIMER_BY_MINUTE_TRANSFERS = AvgTimer.timerMicroSec("McRR:runRaptorForMinute Transfers");


    public McRangeRaptorWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request, int nRounds) {
        super(
                transitData,
                new McRangeRaptorWorkerState<>(
                        nRounds,
                        transitData.numberOfStops(),
                        request.egressLegs,
                        new TransitCalculator(request),
                        new DebugHandlerFactory<>(request.debug)
                ),
                request
        );
    }

    @Override protected Collection<Path<T>> paths() {
        return state.extractPaths();
    }

    @Override
    protected void addPathsForCurrentIteration() {
        // NOOP
    }

    /**
     * Perform a scheduled search
     */
    @Override protected void scheduledSearchForRound() {
        UnsignedIntIterator stops = state.stopsTouchedPreviousRound();
        Iterator<? extends TripPatternInfo<T>> patternIterator = transit.patternIterator(stops);

        while (patternIterator.hasNext()) {
            TripPatternInfo<T> pattern = patternIterator.next();

            TripScheduleBoardSearch<T> search = new TripScheduleBoardSearch<>(pattern, this::skipTripSchedule);

            for (int boardStopPosInPtn = 0; boardStopPosInPtn < pattern.numberOfStopsInPattern(); boardStopPosInPtn++) {
                int boardStopIndex = pattern.stopIndex(boardStopPosInPtn);

                for (AbstractStopArrival<T> boardStop : state.listStopArrivalsPreviousRound(boardStopIndex)) {

                    int earliestBoardTime = earliestBoardTime(boardStop.arrivalTime());
                    boolean found = search.search(earliestBoardTime, boardStopPosInPtn);

                    if (found) {
                        for (int alightStopPosInPtn = boardStopPosInPtn + 1; alightStopPosInPtn < pattern.numberOfStopsInPattern(); alightStopPosInPtn++) {
                            int alightStopIndex = pattern.stopIndex(alightStopPosInPtn);

                            T trip = search.candidateTrip;
                            state.transitToStop(
                                    boardStop,
                                    alightStopIndex,
                                    trip.arrival(alightStopPosInPtn),
                                    trip.departure(boardStopPosInPtn),
                                    trip
                            );
                        }
                    }
                }
            }
        }
        state.commitTransits();
    }

    @Override protected AvgTimer timerRoute() { return TIMER_ROUTE; }
    @Override protected void timerSetup(Runnable setup) { TIMER_ROUTE_SETUP.time(setup); }
    @Override protected void timerRouteByMinute(Runnable routeByMinute) { TIMER_ROUTE_BY_MINUTE.time(routeByMinute); }
    @Override protected AvgTimer timerByMinuteScheduleSearch(){ return TIMER_BY_MINUTE_SCHEDULE_SEARCH; }
    @Override protected AvgTimer timerByMinuteTransfers(){ return TIMER_BY_MINUTE_TRANSFERS; }
}
