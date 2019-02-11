package com.conveyal.r5.profile.entur.rangeraptor.debug;

import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;

/**
 * DestinationArrival adapter.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class DebugHandlerDestinationArrivalAdapter<T extends TripScheduleInfo>
        extends AbstractDebugHandlerAdapter<DestinationArrivalView<T>> {

    DebugHandlerDestinationArrivalAdapter(DebugRequest<T> debug) {
        super(debug, debug.destinationArrivalListener());
    }

    @Override
    protected int stop(DestinationArrivalView<T> arrival) {
        return arrival.previous().stop();
    }

    @Override
    protected Iterable<Integer> stopsVisited(DestinationArrivalView<T> arrival) {
        return arrival.previous().listStopsForDebugging();
    }
}
