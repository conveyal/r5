package com.conveyal.r5.profile.entur.rangeraptor.debug;

import com.conveyal.r5.profile.entur.api.debug.DebugEvent;
import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;

import java.util.Collection;
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

    public AbstractDebugHandlerAdapter(DebugRequest<?> debug, Consumer<DebugEvent<T>> eventListener) {
        this.stops = debug.stops();
        this.path = debug.path();
        this.pathStartAtStopIndex = debug.pathStartAtStopIndex();
        this.eventListener = eventListener;
    }

    @Override
    public boolean isDebug(int stop) {
        return stops.contains(stop) || isDebugTrip(stop);
    }

    @Override
    public void accept(T element, Collection<? extends T> result) {
        if(isDebug(element)) {
            eventListener.accept(DebugEvent.accept(iterationDepartureTime, element, result));
        }
    }

    @Override
    public void reject(T element, Collection<? extends T> result) {
        if(isDebug(element)) {
            eventListener.accept(DebugEvent.reject(iterationDepartureTime, element, result));
        }
    }

    @Override
    public void drop(T element, T droppedByElement) {
        if(isDebug(element)) {
            eventListener.accept(DebugEvent.drop(iterationDepartureTime, element, droppedByElement));
        }
    }

    @Override
    public void setIterationDepartureTime(int iterationDepartureTime) {
        this.iterationDepartureTime = iterationDepartureTime;
    }

    abstract protected int stop(T arrival);

    abstract protected List<Integer> stopsVisited(T arrival);


    /* private members */

    private boolean isDebug(T arrival) {
        return stops.contains(stop(arrival)) || isDebugTrip(arrival);
    }

    private boolean isDebugTrip(T arrival) {
        if (!isDebugTrip(stop(arrival))) {
            return false;
        }

        List<Integer> stopsVisited = stopsVisited(arrival);

        if(stopsVisited.size() > path.size()) {
            return false;
        }

        for (int i = 0; i < stopsVisited.size(); i++) {
            if(!stopsVisited.get(i).equals(path.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isDebugTrip(int stop) {
        return pathStartAtStopIndex <= path.indexOf(stop);
    }
}
