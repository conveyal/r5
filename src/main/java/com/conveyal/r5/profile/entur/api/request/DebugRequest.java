package com.conveyal.r5.profile.entur.api.request;

import com.conveyal.r5.profile.entur.api.debug.DebugEvent;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;


/**
 * This interface configure the amount of debugging you want for your request.
 * Debugging is supported by an event model and event listeners must be provided to
 * receive any debug info.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public interface DebugRequest<T extends TripScheduleInfo> {

    /**
     * List of stops to debug.
     */
    default List<Integer> stops() {
        return Collections.emptyList();
    }

    /**
     * List of stops in a particular path to debug. Only one path can be debugged per request.
     */
    default List<Integer> path() {
        return Collections.emptyList();
    }

    /**
     * The first stop to start recording debug information in the path specified in this request.
     * This will filter away all events in the beginning of the path reducing the number of events significantly;
     * hence make it easier to inspect events towards the end of the trip.
     */
    default int pathStartAtStopIndex() {
        return 0;
    }

    /**
     * Stop arrival debug event listener
     */
    default Consumer<DebugEvent<StopArrivalView<T>>> stopArrivalListener() {
        return null;
    }

    /**
     * Destination arrival debug event listener
     */
    default Consumer<DebugEvent<DestinationArrivalView<T>>> destinationArrivalListener() {
        return null;
    }

    /**
     * Path debug event listener
     */
    default Consumer<DebugEvent<Path<T>>> pathFilteringListener() {
        return null;
    }

    /**
     * Is any debugging enabled.
     */
    default boolean isDebug() {
        return true;
    }
}
