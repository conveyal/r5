package com.conveyal.r5.profile.mcrr.mc;


import com.conveyal.r5.profile.mcrr.api.PathLeg;
import com.conveyal.r5.profile.mcrr.util.DebugState;

import static com.conveyal.r5.profile.mcrr.util.DebugState.Type.Transit;

public final class McTransitStopState extends McStopState {
    private final int boardTime;
    private final int pattern;
    private final int trip;

    McTransitStopState(McStopState previousState, int round, int stopIndex, int time, int boardTime, int pattern, int trip) {
        super(previousState, round, round*2, stopIndex, time);
        this.pattern = pattern;
        this.trip = trip;
        this.boardTime = boardTime;
    }

    @Override public int transitTime() {
        return time();
    }

    @Override public boolean arrivedByTransit() {
        return true;
    }

    @Override public int pattern() {
        return pattern;
    }

    @Override public int trip() {
        return trip;
    }

    @Override public int boardStop() {
        return previousStop();
    }

    @Override public int boardTime() {
        return boardTime;
    }

    @Override
    DebugState.Type type() { return Transit; }

    @Override
    PathLeg mapToLeg() {
        return McPathLeg.createTransitLeg(this);
    }
}
