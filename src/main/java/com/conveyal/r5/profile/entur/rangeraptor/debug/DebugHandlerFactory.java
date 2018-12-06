package com.conveyal.r5.profile.entur.rangeraptor.debug;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;


/**
 * Use this factory to create debug handlers. If a routing request has not enabled debugging
 * NOOP debug handlers are returned - these are designed for optimal performance so the
 * JIT compiler can remove any debugging code.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class DebugHandlerFactory<T extends TripScheduleInfo> {
    private DebugHandler<StopArrivalView<T>> noopStopHandler;
    private DebugHandler<StopArrivalView<T>> stopHandler;
    private DebugHandler<DestinationArrivalView<T>> destinationHandler;
    private DebugHandler<Path<T>> pathHandler;

    public DebugHandlerFactory(DebugRequest<T> request) {
        this.noopStopHandler = DebugHandler.noop();
        this.stopHandler = request.isDebugStopArrivalsEnabled()
                ? new DebugHandlerStopArrivalAdapter<>(request)
                : noopStopHandler;

        this.destinationHandler = request.isDebugDestinationArrivalsEnabled()
                ? new DebugHandlerDestinationArrivalAdapter<>(request)
                : DebugHandler.noop();

        this.pathHandler = request.isDebugPathFiltering()
                ? new DebugHandlerPathAdapter<>(request)
                : DebugHandler.noop();
    }

    public DebugHandler<StopArrivalView<T>> debugStopArrival() {
        return stopHandler;
    }

    public DebugHandler<StopArrivalView<T>> debugStopArrival(int stop) {
        return stopHandler.isDebug(stop) ? stopHandler : noopStopHandler;
    }

    public DebugHandler<DestinationArrivalView<T>> debugDestinationArrival() {
        return destinationHandler;
    }

    public DebugHandler<Path<T>> debugPath() {
        return pathHandler;
    }

    public void setIterationDepartureTime(int departureTime) {
        stopHandler.setIterationDepartureTime(departureTime);
        destinationHandler.setIterationDepartureTime(departureTime);
        pathHandler.setIterationDepartureTime(departureTime);
    }
}
