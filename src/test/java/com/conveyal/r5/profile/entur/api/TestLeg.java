package com.conveyal.r5.profile.entur.api;


public class TestLeg implements AccessLeg, EgressLeg, TransferLeg {
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

    @Override
    public int cost() {
        return 0;
    }
}
