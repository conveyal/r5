package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.PathLeg;
import com.conveyal.r5.profile.entur.util.DebugState;

import static com.conveyal.r5.profile.entur.util.DebugState.Type.Access;

public class McAccessStopState extends McStopState {
    private int fromTime;

    McAccessStopState(int stopIndex, int fromTime,  int accessTime) {
        super(null, 0, 0, stopIndex, fromTime + accessTime);
        this.fromTime = fromTime;
    }

    @Override
    PathLeg mapToLeg() {
        return McPathLeg.createAccessLeg(this, fromTime);
    }

    @Override
    DebugState.Type type() { return Access; }


    public int getFromTime() {
        return fromTime;
    }
}
