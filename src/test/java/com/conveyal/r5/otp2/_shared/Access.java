package com.conveyal.r5.otp2._shared;

class Access extends AbstractStopArrival {
    Access(int stop, int departureTime, int arrivalTime) {
        super(0, stop, departureTime, arrivalTime, 100, null);
    }
    @Override public boolean arrivedByAccessLeg() { return true; }
}
