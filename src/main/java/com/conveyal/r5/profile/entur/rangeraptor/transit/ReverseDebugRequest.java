package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The purpose of this class is to reverse the trip path to match a reverse search,
 * enabling debugging when a reverse search is performed. We do not alter the original
 * request.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class ReverseDebugRequest<T extends TripScheduleInfo> extends DebugRequest<T> {
    private List<Integer> path;

    ReverseDebugRequest(DebugRequest<T> original) {
        super(original);
        this.path = new ArrayList<>(original.path());
        Collections.reverse(path);
    }

    @Override
    public List<Integer> path() {
        return path;
    }
}
