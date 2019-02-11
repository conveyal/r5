package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.debug.DebugEvent;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The purpose of this class is to reverse the trip path to match a reverse search,
 * enabling debugging when a reverse search is performed. We do not alter the original
 * request.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class ReverseDebugRequest<T extends TripScheduleInfo> implements DebugRequest<T> {
    private DebugRequest<T> original;
    private List<Integer> path;

    ReverseDebugRequest(DebugRequest<T> original) {
        this.original = original;
        this.path = new ArrayList<>(original.path());
        Collections.reverse(path);
    }

    @Override
    public List<Integer> stops() {
        return original.stops();
    }

    @Override
    public List<Integer> path() {
        return path;
    }

    @Override
    public int pathStartAtStopIndex() {
        return original.pathStartAtStopIndex();
    }

    @Override
    public Consumer<DebugEvent<StopArrivalView<T>>> stopArrivalListener() {
        return original.stopArrivalListener();
    }

    @Override
    public Consumer<DebugEvent<DestinationArrivalView<T>>> destinationArrivalListener() {
        return original.destinationArrivalListener();
    }

    @Override
    public Consumer<DebugEvent<Path<T>>> pathFilteringListener() {
        return original.pathFilteringListener();
    }

    @Override
    public boolean isDebug() {
        return original.isDebug();
    }
}
