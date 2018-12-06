package com.conveyal.r5.profile.entur.rangeraptor.debug;

import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

import java.util.List;

/**
 * StopArrival adapter.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class DebugHandlerStopArrivalAdapter<T extends TripScheduleInfo>
        extends AbstractDebugHandlerAdapter<StopArrivalView<T>> {

    DebugHandlerStopArrivalAdapter(DebugRequest<T> debug) {
        super(debug, debug.stopArrivalListener());
    }

    @Override
    protected int stop(StopArrivalView<T> arrival) {
        return arrival.stop();
    }

    @Override
    protected List<Integer> stopsVisited(StopArrivalView<T> arrival) {
        return arrival.listStops();
    }
}
