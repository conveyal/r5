package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

public final class McTransferStopArrivalState<T extends TripScheduleInfo> extends McStopArrivalState<T> {
    private final int transferTime;

    public McTransferStopArrivalState(McStopArrivalState<T> previousState, int round, StopArrival stopArrival, int arrivalTime) {
        super(previousState, round, round*2+1,  stopArrival.stop(), arrivalTime, previousState.cost() + stopArrival.cost());
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
}
