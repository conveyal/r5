package com.conveyal.r5.otp2._shared;

import com.conveyal.r5.otp2.api.TestTripSchedule;
import com.conveyal.r5.otp2.api.view.ArrivalView;

class Walk extends AbstractStopArrival {
    Walk(
            int round, int stop, int departureTime, int arrivalTime, ArrivalView<TestTripSchedule> previous
    ) {
        super(round, stop, departureTime, arrivalTime, 100, previous);
    }
    @Override public boolean arrivedByTransfer() {
        return true;
    }
    @Override public int transferFromStop() {
        return previous().stop();
    }
}
