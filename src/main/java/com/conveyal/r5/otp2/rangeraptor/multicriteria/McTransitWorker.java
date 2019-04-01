package com.conveyal.r5.otp2.rangeraptor.multicriteria;

import com.conveyal.r5.otp2.api.transit.IntIterator;
import com.conveyal.r5.otp2.api.transit.TripPatternInfo;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.TransitRoutingStrategy;
import com.conveyal.r5.otp2.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.otp2.rangeraptor.transit.StopFilter;
import com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.otp2.rangeraptor.transit.TripScheduleSearch;


/**
 * The purpose of this class is to implement the multi-criteria specific functionality of
 * the worker.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class McTransitWorker<T extends TripScheduleInfo> implements TransitRoutingStrategy<T> {

    private final McRangeRaptorWorkerState<T> state;
    private final TransitCalculator calculator;
    private final StopFilter stopFilter;

    private TripPatternInfo<T> pattern;
    private TripScheduleSearch<T> tripSearch;

    public McTransitWorker(McRangeRaptorWorkerState<T> state, StopFilter stopFilter, TransitCalculator calculator) {
        this.state = state;
        this.stopFilter = stopFilter;
        this.calculator = calculator;
    }

    @Override
    public void prepareForTransitWith(TripPatternInfo<T> pattern, TripScheduleSearch<T> tripSearch) {
        this.pattern = pattern;
        this.tripSearch = tripSearch;
    }

    @Override
    public void routeTransitAtStop(int boardStopPos) {
        final int nPatternStops = pattern.numberOfStopsInPattern();
        int boardStopIndex = pattern.stopIndex(boardStopPos);

        for (AbstractStopArrival<T> boardStop : state.listStopArrivalsPreviousRound(boardStopIndex)) {

            int earliestBoardTime = calculator.earliestBoardTime(boardStop.arrivalTime());
            boolean found = tripSearch.search(earliestBoardTime, boardStopPos);

            if (found) {
                T trip = tripSearch.getCandidateTrip();
                IntIterator patternStops = calculator.patternStopIterator(boardStopPos, nPatternStops);

                while (patternStops.hasNext()) {
                    int alightStopPos = patternStops.next();
                    int alightStopIndex = pattern.stopIndex(alightStopPos);

                    if(stopFilter.allowStopVisit(alightStopIndex)) {
                        state.transitToStop(
                                boardStop,
                                alightStopIndex,
                                trip.arrival(alightStopPos),
                                trip.departure(boardStopPos),
                                trip
                        );
                    }
                }
            }
        }
    }
}
