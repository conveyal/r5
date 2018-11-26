package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.TransferLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

public final class TransferStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {

    public TransferStopArrival(AbstractStopArrival<T> previousState, int round, TransferLeg transferLeg, int arrivalTime) {
        super(
                previousState,
                round,
                transferLeg.stop(),
                arrivalTime - transferLeg.durationInSeconds(),
                arrivalTime,
                previousState.cost() + transferLeg.cost()
        );
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
