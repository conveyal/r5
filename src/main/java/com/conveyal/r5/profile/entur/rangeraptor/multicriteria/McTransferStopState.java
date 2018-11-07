package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.DebugState;

public final class McTransferStopState<T extends TripScheduleInfo> extends McStopState<T> {
    private final int transferTime;

    McTransferStopState(McStopState<T> previousState, int round, StopArrival stopArrival, int arrivalTime) {
        super(previousState, round, round*2+1,  stopArrival, arrivalTime);
        this.transferTime = stopArrival.durationInSeconds();
    }

    @Override
    public int transferTime() {
        return transferTime;
    }

    @Override
    public int transferFromStop() {
        return previousStop();
    }

    @Override
    public boolean arrivedByTransfer() {
        return true;
    }

    @Override
    DebugState.Type type() { return DebugState.Type.Transfer; }

}
