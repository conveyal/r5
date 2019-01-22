package com.conveyal.r5.profile.entur.api;


import com.conveyal.r5.profile.entur.api.transit.TransferLeg;

public class TestLeg implements TransferLeg {
    private final int stop;
    private final int durationInSeconds;
    private final int cost;

    public TestLeg(int stop, int durationInSeconds, int cost) {
        this.stop = stop;
        this.durationInSeconds = durationInSeconds;
        this.cost = cost;
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
        return cost;
    }
}
