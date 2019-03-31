package com.conveyal.r5.otp2.rangeraptor.debug;

import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.request.DebugRequest;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.ArrivalView;
import com.conveyal.r5.otp2.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.otp2.rangeraptor.view.DebugHandler;
import com.conveyal.r5.otp2.util.paretoset.ParetoSetEventListener;


/**
 * Use this factory to create debug handlers. If a routing request has not enabled debugging
 * {@code null} is returned. Use the {@link #isDebugStopArrival(int)} like methods before
 * retrieving a handler.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class DebugHandlerFactory<T extends TripScheduleInfo> {
    private DebugHandler<ArrivalView<T>> stopHandler;
    private DebugHandler<Path<T>> pathHandler;

    public DebugHandlerFactory(DebugRequest<T> request, WorkerLifeCycle lifeCycle) {
        this.stopHandler = isDebug(request.stopArrivalListener())
                ? new DebugHandlerStopArrivalAdapter<>(request, lifeCycle)
                : null;

        this.pathHandler = isDebug(request.pathFilteringListener())
                ? new DebugHandlerPathAdapter<>(request, lifeCycle)
                : null;
    }

    /* Stop Arrival */

    public boolean isDebugStopArrival() {
        return stopHandler != null;
    }

    public boolean isDebugStopArrival(int stop) {
        return stopHandler != null && stopHandler.isDebug(stop);
    }

    public DebugHandler<ArrivalView<T>> debugStopArrival() {
        return stopHandler;
    }

    public ParetoSetEventListener<ArrivalView<T>> paretoSetStopArrivalListener(int stop) {
        return isDebugStopArrival(stop) ? new ParetoSetDebugHandlerAdapter<>(stopHandler) : null;
    }


    /* path */

    @SuppressWarnings("WeakerAccess")
    public boolean isDebugPath() {
        return pathHandler != null;
    }

    public ParetoSetDebugHandlerAdapter<Path<T>> paretoSetDebugPathListener() {
        return isDebugPath() ? new ParetoSetDebugHandlerAdapter<>(pathHandler) : null;
    }

    /* private methods */

    private boolean isDebug(Object handler) {
        return handler != null;
    }
}
