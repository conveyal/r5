package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.AbstractRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleSearch;


/**
 * The purpose TODO
 * <p>
 * The algorithm used herein is described in TODO
 * <p>
 * This class originated as a rewrite of our RAPTOR code that TODO
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class McRangeRaptorWorker<T extends TripScheduleInfo> extends AbstractRangeRaptorWorker<T, McRangeRaptorWorkerState<T>> {

    private TripPatternInfo<T> pattern;
    private TripScheduleSearch<T> tripSearch;

    public McRangeRaptorWorker(SearchContext<T> context) {
        super(
                context,
                new McRangeRaptorWorkerState<>(
                        nRounds(context.tuningParameters()),
                        context.transit().numberOfStops(),
                        context.request().egressLegs(),
                        context.calculator(),
                        context.debugFactory()
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
    protected final void performTransitForRoundAndPatternAtStop(int boardStopPositionInPattern) {
        final int nPatternStops = pattern.numberOfStopsInPattern();
        int boardStopIndex = pattern.stopIndex(boardStopPositionInPattern);

        for (AbstractStopArrival<T> boardStop : state.listStopArrivalsPreviousRound(boardStopIndex)) {

            int earliestBoardTime = calculator().addBoardSlack(boardStop.arrivalTime());
            boolean found = tripSearch.search(earliestBoardTime, boardStopPositionInPattern);

            if (found) {
                for (int alightStopPos = boardStopPositionInPattern + 1; alightStopPos < nPatternStops; alightStopPos++) {
                    int alightStopIndex = pattern.stopIndex(alightStopPos);

                    T trip = tripSearch.getCandidateTrip();

                    state.transitToStop(
                            boardStop,
                            alightStopIndex,
                            trip.arrival(alightStopPos),
                            trip.departure(boardStopPositionInPattern),
                            trip
                    );
                }
            }
        }
    }
}
