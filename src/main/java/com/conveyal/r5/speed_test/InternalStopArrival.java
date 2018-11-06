package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.entur.api.StopArrival;

class InternalStopArrival implements StopArrival {
    private final int stop, durationInSeconds;

    InternalStopArrival(int stop, int durationInSeconds) {
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
        return durationInSeconds;
    }
}
