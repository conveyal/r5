package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.path;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.DebugState;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.DestinationArrival;


/**
 * Build a path from a destination arrival - this maps between the domain of routing
 * to the domain of collecting the result paths. All values not needed for
 * routing is computed as part of this mapping.
 */
public class McPathBuilder<T extends TripScheduleInfo> {

    public Path2<T> buildPath(DestinationArrival<T> egressLeg) {
        debugPath(egressLeg.getPreviousState());
        return new McPath<>(egressLeg);
    }

    private void debugPath(AbstractStopArrival<T> egressStop) {
        DebugState.debugStopHeader("MC - CREATE PATH FOR EGRESS STOP: " + egressStop.stopIndex());

        if(DebugState.isDebug(egressStop.stopIndex())) {
            for (AbstractStopArrival p : egressStop.path()) {
                p.debug();
            }
        }
    }
}
