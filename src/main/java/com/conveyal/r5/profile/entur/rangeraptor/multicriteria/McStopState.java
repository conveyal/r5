package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.rangeraptor.standard.StopState;
import com.conveyal.r5.profile.entur.util.DebugState;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSortable;

import java.util.LinkedList;
import java.util.List;

public abstract class McStopState implements StopState, ParetoSortable {
    private final McStopState previousState;
    private final int round;
    private final int stopIndex;
    private final int time;
    private final int roundPareto;

    McStopState(McStopState previousState, int round, int roundPareto, int stopIndex, int time) {
        this.previousState = previousState;
        this.round = round;
        this.stopIndex = stopIndex;
        this.time = time;
        this.roundPareto = roundPareto;
    }

    final int previousStop() {
        return previousState.stopIndex;
    }

    final McStopState previousState() {
        return previousState;
    }

    public final int stopIndex() {
        return stopIndex;
    }

    public final int round() {
        return round;
    }

    @Override
    public final int time() {
        return time;
    }

    @Override
    public int transitTime() {
        return UNREACHED;
    }

    @Override
    public boolean arrivedByTransit() {
        return false;
    }

    @Override
    public int pattern() {
        return NOT_SET;
    }

    @Override
    public int trip() {
        return NOT_SET;
    }

    @Override
    public int transferTime() {
        return NOT_SET;
    }

    @Override
    public int boardStop() {
        return NOT_SET;
    }

    @Override
    public int boardTime() {
        return UNREACHED;
    }

    @Override
    public int transferFromStop() {
        return NOT_SET;
    }

    @Override
    public boolean arrivedByTransfer() {
        return false;
    }

    @Override
    public final int paretoValue1() {
        return time;
    }

    @Override
    public final int paretoValue2() {
        return round;
    }

    @Override
    public String toString() {
        return asString(type().name(), round(), previousState == null ? -1 : previousState.stopIndex);
    }

    abstract DebugState.Type type();

    public void debug() {
        DebugState.debugStop(type(), round, stopIndex, this);
    }

    public List<McStopState> path() {
        List<McStopState> path = new LinkedList<>();
        McStopState current = this;

        path.add(current);

        while (current.previousState != null) {
            current = current.previousState;
            path.add(0, current);
        }
        return path;
    }
}
