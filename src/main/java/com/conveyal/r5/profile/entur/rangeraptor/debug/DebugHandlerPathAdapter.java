package com.conveyal.r5.profile.entur.rangeraptor.debug;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.List;

/**
 * Path adapter.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class DebugHandlerPathAdapter <T extends TripScheduleInfo> extends AbstractDebugHandlerAdapter<Path<T>> {

    DebugHandlerPathAdapter(DebugRequest<T> debug) {
        super(debug, debug.pathFilteringListener());
    }

    @Override
    protected int stop(Path<T> path) {
        return path.egressLeg().fromStop();
    }

    @Override
    protected List<Integer> stopsVisited(Path<T> path) {
        return path.listStops();
    }
}
