package com.conveyal.r5.otp2.api.request;

import com.conveyal.r5.otp2.api.debug.DebugEvent;
import com.conveyal.r5.otp2.api.debug.DebugLogger;
import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.ArrivalView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Mutable version of {@link DebugRequest}.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class DebugRequestBuilder<T extends TripScheduleInfo> {
    private final List<Integer> stops = new ArrayList<>();
    private final List<Integer> path = new ArrayList<>();
    private int debugPathFromStopIndex;
    private Consumer<DebugEvent<ArrivalView<T>>> stopArrivalListener;
    private Consumer<DebugEvent<Path<T>>> pathFilteringListener;
    private DebugLogger logger;


    DebugRequestBuilder(DebugRequest<T> debug) {
        this.stops.addAll(debug.stops());
        this.path.addAll(debug.path());
        this.debugPathFromStopIndex = debug.debugPathFromStopIndex();
        this.stopArrivalListener = debug.stopArrivalListener();
        this.pathFilteringListener = debug.pathFilteringListener();
        this.logger = debug.logger();
    }


    public List<Integer> stops() {
        return stops;
    }

    public DebugRequestBuilder<T> addStops(Collection<Integer> stops) {
        this.stops.addAll(stops);
        return this;
    }

    public List<Integer> path() {
        return path;
    }

    public DebugRequestBuilder<T> addPath(Collection<Integer> path) {
        if(!path.isEmpty()) {
            throw new IllegalStateException("The API support only one debug path. Existing: " + this.path + ", new: " + path);
        }
        this.path.addAll(path);
        return this;
    }

    public int debugPathFromStopIndex() {
        return debugPathFromStopIndex;
    }

    public DebugRequestBuilder<T> debugPathFromStopIndex(Integer debugPathStartAtStopIndex) {
        this.debugPathFromStopIndex = debugPathStartAtStopIndex;
        return this;
    }

    public Consumer<DebugEvent<ArrivalView<T>>> stopArrivalListener() {
        return stopArrivalListener;
    }

    public DebugRequestBuilder<T> stopArrivalListener(Consumer<DebugEvent<ArrivalView<T>>> stopArrivalListener) {
        this.stopArrivalListener = stopArrivalListener;
        return this;
    }

    public Consumer<DebugEvent<Path<T>>> pathFilteringListener() {
        return pathFilteringListener;
    }

    public DebugRequestBuilder<T> pathFilteringListener(Consumer<DebugEvent<Path<T>>> pathFilteringListener) {
        this.pathFilteringListener = pathFilteringListener;
        return this;
    }

    public DebugLogger logger() {
        return logger;
    }

    public DebugRequestBuilder<T> logger(DebugLogger logger) {
        this.logger = logger;
        return this;
    }

    public DebugRequestBuilder<T> reverseDebugRequest() {
        Collections.reverse(this.path);
        return this;
    }

    public DebugRequest<T> build() {
        return new DebugRequest<>(this);
    }

}
