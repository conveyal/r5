package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.AbstractRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleSearch;
import com.conveyal.r5.profile.entur.rangeraptor.view.Heuristics;


/**
 * The purpose of this class is to implement the multi-criteria specific functonallity of
 * the worker.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class McRangeRaptorWorker<T extends TripScheduleInfo> extends AbstractRangeRaptorWorker<T, McRangeRaptorWorkerState<T>> {

    private TripPatternInfo<T> pattern;
    private TripScheduleSearch<T> tripSearch;

    public McRangeRaptorWorker(SearchContext<T> context, Heuristics heuristics) {
        super(
                context,
                new McRangeRaptorWorkerState<>(
                        context.nStops(),
                        context.tuningParameters().relaxCostAtDestinationArrival(),
                        context.egressLegs(),
                        heuristics,
                        context.roundProvider(),
                        context.costCalculator(),
                        context.calculator(),
                        context.debugFactory(),
                        context.lifeCycle()
                )
        );
    }

    @Override
    protected final void prepareTransitForRoundAndPattern(TripPatternInfo<T> pattern, TripScheduleSearch<T> tripSearch) {
        this.pattern = pattern;
        this.tripSearch = tripSearch;
    }

    /**
     * Perform a scheduled search
     */
    @Override
    protected final void performTransitForRoundAndPatternAtStop(int boardStopPos) {
        final int nPatternStops = pattern.numberOfStopsInPattern();
        int boardStopIndex = pattern.stopIndex(boardStopPos);

        for (AbstractStopArrival<T> boardStop : state.listStopArrivalsPreviousRound(boardStopIndex)) {

            int earliestBoardTime = calculator().earliestBoardTime(boardStop.arrivalTime());
            boolean found = tripSearch.search(earliestBoardTime, boardStopPos);

            if (found) {
                T trip = tripSearch.getCandidateTrip();
                IntIterator patternStops = calculator().patternStopIterator(boardStopPos, nPatternStops);

                while (patternStops.hasNext()) {
                    int alightStopPos = patternStops.next();
                    state.transitToStop(
                            boardStop,
                            pattern.stopIndex(alightStopPos),
                            trip.arrival(alightStopPos),
                            trip.departure(boardStopPos),
                            trip
                    );
                }
            }
        }
    }
}
