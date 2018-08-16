package com.conveyal.r5.profile.mcrr.mc;


import com.conveyal.r5.profile.mcrr.util.DebugState;

import static com.conveyal.r5.profile.mcrr.util.DebugState.Type.Access;

public class McAccessStopState extends McStopState {

    McAccessStopState(int stopIndex, int time) {
        super(null, 0, 0, stopIndex, time);
    }

    @Override
    DebugState.Type type() { return Access; }
}
