package com.conveyal.r5.profile.entur.rangeraptor.debug;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSetEventListener;


/**
 * Use this factory to create debug handlers. If a routing request has not enabled debugging
 * {@code null} is returned. Use the {@link #isDebugStopArrival(int)} like methods before
 * retrieving a handler.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class DebugHandlerFactory<T extends TripScheduleInfo> {
    private DebugHandler<StopArrivalView<T>> stopHandler;
    private DebugHandler<DestinationArrivalView<T>> destinationHandler;
    private DebugHandler<Path<T>> pathHandler;

    public DebugHandlerFactory(DebugRequest<T> request, WorkerLifeCycle lifeCycle) {
        this.stopHandler = isDebug(request, request.stopArrivalListener())
                ? new DebugHandlerStopArrivalAdapter<>(request, lifeCycle)
                : null;

        this.destinationHandler = isDebug(request, request.destinationArrivalListener())
                ? new DebugHandlerDestinationArrivalAdapter<>(request, lifeCycle)
                : null;

        this.pathHandler = isDebug(request, request.pathFilteringListener())
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

    public DebugHandler<StopArrivalView<T>> debugStopArrival() {
        return stopHandler;
    }

    public ParetoSetEventListener<StopArrivalView<T>> paretoSetStopArrivalListener(int stop) {
        return isDebugStopArrival(stop) ? new ParetoSetDebugHandlerAdapter<>(stopHandler) : null;
    }


    /* destination arrival */

    @SuppressWarnings("WeakerAccess")
    public boolean isDebugDestinationArrival() {
        return destinationHandler != null;
    }

    public DebugHandler<DestinationArrivalView<T>> debugDestinationArrival() {
        return destinationHandler;
    }

    public ParetoSetEventListener<DestinationArrivalView<T>> paretoSetDestinationArrivalListener() {
        return isDebugDestinationArrival() ? new ParetoSetDebugHandlerAdapter<>(destinationHandler) : null;
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

    private boolean isDebug(DebugRequest<T> request, Object handler) {
        return request.isDebug() && handler != null;
    }
}
