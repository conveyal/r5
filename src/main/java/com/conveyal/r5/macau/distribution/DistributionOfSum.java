package com.conveyal.r5.macau.distribution;

/**
 * Not the sum of two distribution functions, but the distribution of the sum of two random variables.
 * For probability density or mass functions this is the convolution of their distributions.
 * For cumulative probabilities it is scaled differently.
 *
 * The bounds are not necessarily implemented yet to be tight, but they can and should be.
 */
public class DistributionOfSum implements Distribution {

    private Distribution a;
    private Distribution b;

    public DistributionOfSum (Distribution a, Distribution b) {
        this.a = a;
        this.b = b;
    }

    @Override
    public int minTime () {
        return a.minTime() + b.minTime();
    }

    @Override
    public int maxTime () {
        return a.maxTime() + b.maxTime();
    }

    @Override
    public double maxCumulativeProbability () {
        return a.maxCumulativeProbability() * b.maxCumulativeProbability();
    }

    @Override
    public double probabilityDensity (int t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double cumulativeProbability (int t) {
        throw new UnsupportedOperationException();
    }
}

