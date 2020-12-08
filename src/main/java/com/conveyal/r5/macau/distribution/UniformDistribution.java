package com.conveyal.r5.macau.distribution;

import static com.google.common.base.Preconditions.checkArgument;

public class UniformDistribution implements Distribution {

    private int startInclusive;

    private int endExclusive;

    private double density;

    public UniformDistribution (int startInclusive, int endExclusive) {
        checkArgument(endExclusive > startInclusive);
        this.startInclusive = startInclusive;
        this.endExclusive = endExclusive;
        density = 1/(endExclusive - startInclusive);
    }

    @Override
    public int minTime () {
        return startInclusive;
    }

    @Override
    public int maxTime () {
        return endExclusive;
    }

    @Override
    public double maxCumulativeProbability () {
        return 1;
    }

    @Override
    public double probabilityDensity (int t) {
        return density;
    }

    @Override
    public double cumulativeProbability (int t) {
        return density * (t - startInclusive + 1);
    }

}
