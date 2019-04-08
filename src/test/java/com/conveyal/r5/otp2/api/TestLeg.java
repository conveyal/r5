package com.conveyal.r5.otp2.api;


import com.conveyal.r5.otp2.api.transit.TransferLeg;

public class TestLeg implements TransferLeg {
    private final int stop;
    private final int durationInSeconds;

    public TestLeg(int stop, int durationInSeconds) {
        this.stop = stop;
        this.durationInSeconds = durationInSeconds;
    }

    @Override
    public int stop() {
        return stop;
    }

    @Override
    public int durationInSeconds() {
        return durationInSeconds;
    }
}
