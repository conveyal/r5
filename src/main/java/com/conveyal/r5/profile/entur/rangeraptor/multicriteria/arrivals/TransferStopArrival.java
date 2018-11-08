package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

public final class TransferStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
    private final int transferTime;

    public TransferStopArrival(AbstractStopArrival<T> previousState, int round, StopArrival stopArrival, int arrivalTime) {
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
