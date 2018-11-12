package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.TransferLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

public final class TransferStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
    private final int transferTime;

    public TransferStopArrival(AbstractStopArrival<T> previousState, int round, TransferLeg transferLeg, int arrivalTime) {
        super(previousState, round, round*2+1,  transferLeg.stop(), arrivalTime, previousState.cost() + transferLeg.cost());
        this.transferTime = transferLeg.durationInSeconds();
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
