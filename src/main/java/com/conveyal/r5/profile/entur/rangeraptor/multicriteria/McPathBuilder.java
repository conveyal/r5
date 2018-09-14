package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.util.DebugState;


/**
 * TODO TGR
 */
class McPathBuilder {

    Path2 extractPathsForStop(McStopState egressStop, int egressDurationInSeconds) {
        if (!egressStop.arrivedByTransit()) {
            return null;
        }
        debugPath(egressStop);
        return new McPath(egressStop.path(), egressDurationInSeconds);
    }

    private void debugPath(McStopState egressStop) {
        DebugState.debugStopHeader("MC - CREATE PATH FOR EGRESS STOP: " + egressStop.stopIndex());

        if(DebugState.isDebug(egressStop.stopIndex())) {
            for (McStopState p : egressStop.path()) {
                p.debug();
            }
        }
    }
}
