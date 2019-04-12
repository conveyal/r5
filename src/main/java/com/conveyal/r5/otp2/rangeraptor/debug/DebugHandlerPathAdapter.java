package com.conveyal.r5.otp2.rangeraptor.debug;

import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.request.DebugRequest;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.WorkerLifeCycle;

import java.util.List;

/**
 * Path adapter.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class DebugHandlerPathAdapter <T extends TripScheduleInfo> extends AbstractDebugHandlerAdapter<Path<T>> {

    DebugHandlerPathAdapter(DebugRequest<T> debug, WorkerLifeCycle lifeCycle) {
        super(debug, debug.pathFilteringListener(), lifeCycle);
    }

    @Override
    protected int stop(Path<T> path) {
        return path.egressLeg().fromStop();
    }

    @Override
    protected List<Integer> stopsVisited(Path<T> path) {
        return path.listStops();
    }
}
