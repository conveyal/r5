package com.conveyal.r5.profile.entur.rangeraptor.debug;

import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.LifeCyclePublisher;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

/**
 * StopArrival adapter.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class DebugHandlerStopArrivalAdapter<T extends TripScheduleInfo>
        extends AbstractDebugHandlerAdapter<StopArrivalView<T>> {

    DebugHandlerStopArrivalAdapter(DebugRequest<T> debug, LifeCyclePublisher lifeCycle) {
        super(debug, debug.stopArrivalListener(), lifeCycle);
    }

    @Override
    protected int stop(StopArrivalView<T> arrival) {
        return arrival.stop();
    }

    @Override
    protected Iterable<Integer> stopsVisited(StopArrivalView<T> arrival) {
        return arrival.listStopsForDebugging();
    }
}
