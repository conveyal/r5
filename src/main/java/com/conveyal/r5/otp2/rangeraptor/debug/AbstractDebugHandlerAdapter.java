package com.conveyal.r5.otp2.rangeraptor.debug;

import com.conveyal.r5.otp2.api.debug.DebugEvent;
import com.conveyal.r5.otp2.api.request.DebugRequest;
import com.conveyal.r5.otp2.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.otp2.rangeraptor.view.DebugHandler;

import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Generic abstract implementation of the {@link DebugHandler} interface. The main purpose
 * is provide a common logic for the adapters between the Range Raptor domain and the
 * outside client - the event listeners and the request API.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
abstract class AbstractDebugHandlerAdapter<T> implements DebugHandler<T> {
    private final List<Integer> stops;
    private final List<Integer> path;
    private final int pathStartAtStopIndex;
    private final Consumer<DebugEvent<T>> eventListener;
    private int iterationDepartureTime = -1;

    AbstractDebugHandlerAdapter(
            DebugRequest<?> debugRequest,
            Consumer<DebugEvent<T>> eventListener,
            WorkerLifeCycle lifeCycle
    ) {
        this.stops = debugRequest.stops();
        this.path = debugRequest.path();
        this.pathStartAtStopIndex = debugRequest.debugPathFromStopIndex();
        this.eventListener = eventListener;

        // Attach debugger to RR life cycle to receive iteration setup events
        lifeCycle.onSetupIteration(this::setupIteration);
    }

    @Override
    public boolean isDebug(int stop) {
        return stops.contains(stop) || isDebugTrip(stop);
    }

    @Override
    public void accept(T element) {
        // The "if" is needed because this is the first time we are able to check trip paths
        if (isDebugStopOrTripPath(element)) {
            eventListener.accept(DebugEvent.accept(iterationDepartureTime, element));
        }
    }

    @Override
    public void reject(T element, T rejectedByElement, String reason) {
        // The "if" is needed because this is the first time we are able to check trip paths
        if (isDebugStopOrTripPath(element)) {
            eventListener.accept(DebugEvent.reject(iterationDepartureTime, element, rejectedByElement, reason));
        }
    }

    @Override
    public void drop(T element, T droppedByElement, String reason) {
        // The "if" is needed because this is the first time we are able to check trip paths
        if (isDebugStopOrTripPath(element)) {
            eventListener.accept(DebugEvent.drop(iterationDepartureTime, element, droppedByElement, reason));
        }
    }

    abstract protected int stop(T arrival);

    abstract protected Iterable<Integer> stopsVisited(T arrival);


    /* private members */

    private void setupIteration ( int iterationDepartureTime){
        this.iterationDepartureTime = iterationDepartureTime;
    }

    /**
     * Check if a stop exist among the trip path stops witch should be debugged.
     */
    private boolean isDebugTrip(int stop) {
        return pathStartAtStopIndex <= path.indexOf(stop);
    }

    private boolean isDebugStopOrTripPath(T arrival) {
        return stops.contains(stop(arrival)) || isDebugTripPath(arrival);
    }

    private boolean isDebugTripPath(T arrival) {
        if (!isDebugTrip(stop(arrival))) {
            return false;
        }

        Iterator<Integer> stopsVisited = stopsVisited(arrival).iterator();
        Iterator<Integer> pathStops = path.iterator();

        while (stopsVisited.hasNext()) {
            if (!pathStops.hasNext()) {
                return false;
            }
            Integer visitedStop = stopsVisited.next();
            Integer pathStop = pathStops.next();

            if (!visitedStop.equals(pathStop)) {
                return false;
            }
        }
        return true;
    }
}
