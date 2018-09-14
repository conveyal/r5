package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.util.DebugState;

import static com.conveyal.r5.profile.entur.util.DebugState.Type.Access;

class McAccessStopState extends McStopState {
    final int accessDuationInSeconds;
    final int boardSlackInSeconds;


    McAccessStopState(int stopIndex, int fromTime,  int accessDuationInSeconds, int boardSlackInSeconds) {
        super(null, 0, 0, stopIndex, fromTime + accessDuationInSeconds);
        this.accessDuationInSeconds = accessDuationInSeconds;
        this.boardSlackInSeconds = boardSlackInSeconds;
    }

    @Override
    DebugState.Type type() { return Access; }

}
