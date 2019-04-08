package com.conveyal.r5.otp2._shared;

import com.conveyal.r5.otp2.api.TestTripSchedule;
import com.conveyal.r5.otp2.api.view.ArrivalView;

class Bus extends AbstractStopArrival {
    private final TestTripSchedule trip;

    Bus(
            int round, int stop, int departureTime, int arrivalTime, TestTripSchedule trip,
            ArrivalView<TestTripSchedule> previous
    ) {
        super(round, stop, departureTime, arrivalTime, 100, previous);
        this.trip = trip;
    }
    @Override public int boardStop() { return previous().stop(); }
    @Override public TestTripSchedule trip() { return trip; }
    @Override public boolean arrivedByTransit() { return true; }
}
