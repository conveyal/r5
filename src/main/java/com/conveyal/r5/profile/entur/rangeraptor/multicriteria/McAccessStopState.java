package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.util.DebugState;

import static com.conveyal.r5.profile.entur.util.DebugState.Type.Access;

class McAccessStopState extends McStopState {
    final int accessDuationInSeconds;
    final int boardSlackInSeconds;


    McAccessStopState(StopArrival stopArrival, int fromTime, int boardSlackInSeconds) {
        super(null, 0, 0, stopArrival.stop(), fromTime + stopArrival.durationInSeconds());
        this.accessDuationInSeconds = stopArrival.durationInSeconds();
        this.boardSlackInSeconds = boardSlackInSeconds;
    }

    @Override
    DebugState.Type type() { return Access; }

}
