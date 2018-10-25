package com.conveyal.r5.profile.entur.api;


public class AStopArrival implements StopArrival {
    private final int stop;
    private final int durationInSeconds;

    public AStopArrival(int stop, int durationInSeconds) {
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
