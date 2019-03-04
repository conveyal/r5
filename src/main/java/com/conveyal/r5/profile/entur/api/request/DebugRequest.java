package com.conveyal.r5.profile.entur.api.request;

import com.conveyal.r5.profile.entur.api.debug.DebugEvent;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;


/**
 * This class configure the amount of debugging you want for your request.
 * Debugging is supported by an event model and event listeners must be provided to
 * receive any debug info.
 * <p/>
 * To debug unexpected results is sometimes very time consuming. This class make it possible
 * to list all stop arrival events during the search for a given list of stops and/or a path.
 * <p/>
 * The debug events are not returned as part of the result, instead they are posted
 * to registered listeners. The events are temporary objects; hence you should not
 * hold a reference to the event elements or to any part of it after the listener callback
 * completes.
 * <p/>
 * One of the benefits of the event based return strategy is that the events are returned
 * even in the case of an exception or entering a endless loop. You don´t need to wait
 * for the result to start analyze the results.
 *
 * <h3>Debugging stops</h3>
 * By providing a small set of stops to debug, a list of all events for those stops
 * are returned. This can be useful both to understand the algorithm and to debug events
 * at a particular stop.
 *
 * <h3>Debugging path</h3>
 * To debug a path(or trip), provide the list of stops and a index. You will then only get
 * events for that particular sequence of stops starting with the stop at the given index.
 * This is very effect if you expect a trip and don´t get it. Most likely you will get a
 * REJECT or DROP event for your trip in return. You will also get a list of tips dominating
 * the particular trip.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class DebugRequest<T extends TripScheduleInfo> {

    /**
     * Return a debug request with defaults values.
     */
    static <T extends TripScheduleInfo> DebugRequest<T> defaults() {
        return new DebugRequest<>();
    }

    private final List<Integer> stops;
    private final List<Integer> path;
    private final int pathStartAtStopIndex;
    private final Consumer<DebugEvent<StopArrivalView<T>>> stopArrivalListener;
    private final Consumer<DebugEvent<DestinationArrivalView<T>>> destinationArrivalListener;
    private final Consumer<DebugEvent<Path<T>>> pathFilteringListener;

    private DebugRequest() {
        stops = Collections.emptyList();
        path = Collections.emptyList();
        pathStartAtStopIndex = 0;
        stopArrivalListener = null;
        destinationArrivalListener = null;
        pathFilteringListener = null;
    }

    DebugRequest(RequestBuilder<T> builder) {
        this.stops = Collections.unmodifiableList(builder.debugStops());
        this.path = Collections.unmodifiableList(builder.debugPath());
        this.pathStartAtStopIndex = builder.debugPathStartAtStopIndex();
        this.stopArrivalListener = builder.stopArrivalListener();
        this.destinationArrivalListener = builder.destinationArrivalListener();
        this.pathFilteringListener = builder.pathFilteringListener();
    }


    /**
     * List of stops to debug.
     */
    public List<Integer> stops() {
        return stops;
    }

    /**
     * List of stops in a particular path to debug. Only one path can be debugged per request.
     */
    public List<Integer> path() {
        return path;
    }

    /**
     * The first stop to start recording debug information in the path specified in this request.
     * This will filter away all events in the beginning of the path reducing the number of events significantly;
     * hence make it easier to inspect events towards the end of the trip.
     */
    public int pathStartAtStopIndex() {
        return pathStartAtStopIndex;
    }

    /**
     * Stop arrival debug event listener
     */
    public Consumer<DebugEvent<StopArrivalView<T>>> stopArrivalListener() {
        return stopArrivalListener;
    }

    /**
     * Destination arrival debug event listener
     */
    public Consumer<DebugEvent<DestinationArrivalView<T>>> destinationArrivalListener() {
        return destinationArrivalListener;
    }

    /**
     * Path debug event listener
     */
    public Consumer<DebugEvent<Path<T>>> pathFilteringListener() {
        return pathFilteringListener;
    }

    /**
     * Is any debugging enabled. Either stops or path exist.
     */
    public boolean isDebug() {
        return !stops.isEmpty() || !path.isEmpty();
    }

    @Override
    public String toString() {
        return "DebugRequest{" +
                "stops=" + stops +
                ", path=" + path +
                ", pathStartAtStopIndex=" + pathStartAtStopIndex +
                ", stopArrivalListener=" + enabled(stopArrivalListener) +
                ", destinationArrivalListener=" + enabled(destinationArrivalListener) +
                ", pathFilteringListener=" + enabled(pathFilteringListener) +
                '}';
    }

    private static String enabled(Object obj) {
        return obj == null ? "null" : "enabled";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DebugRequest<?> that = (DebugRequest<?>) o;
        return pathStartAtStopIndex == that.pathStartAtStopIndex &&
                Objects.equals(stops, that.stops) &&
                Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stops, path, pathStartAtStopIndex);
    }
}
