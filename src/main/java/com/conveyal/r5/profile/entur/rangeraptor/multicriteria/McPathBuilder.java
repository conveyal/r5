package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.DebugState;


/**
 * TODO TGR
 */
class McPathBuilder<T extends TripScheduleInfo> {

    Path2<T> extractPathsForStop(McStopState<T> egressStop, int egressDurationInSeconds) {
        if (!egressStop.arrivedByTransit()) {
            return null;
        }
        debugPath(egressStop);
        return new McPath<T>(egressStop.path(), egressDurationInSeconds);
    }

    private void debugPath(McStopState<T> egressStop) {
        DebugState.debugStopHeader("MC - CREATE PATH FOR EGRESS STOP: " + egressStop.stopIndex());

        if(DebugState.isDebug(egressStop.stopIndex())) {
            for (McStopState p : egressStop.path()) {
                p.debug();
            }
        }
    }
}
