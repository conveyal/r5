package com.conveyal.r5.macau.distribution;

/** All probability concentrated at a single integer value. */
public class KroneckerDelta implements Distribution {

    private final int time;

    public KroneckerDelta (int time) {
        this.time = time;
    }

    @Override
    public int minTime () {
        return time;
    }

    @Override
    public int maxTime () {
        return time;
    }

    @Override
    public double maxCumulativeProbability () {
        return 1;
    }

    @Override
    public double probabilityDensity (int t) {
        if (t == time) return 1;
        else return 0;
    }

    @Override
    public double cumulativeProbability (int t) {
        if (t < time) return 0;
        else return 1;
    }
}
