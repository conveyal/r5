package com.conveyal.r5.macau.distribution;

/**
 * This is equivalent to the distribution of a sum of a Kronecker delta with any other random variable.
 * This special case is much easier to calculate because one of its terms has no width, reducing convolution to addition.
 */
public class RightShift implements Distribution {

    private Distribution distribution;

    private int shiftAmount;

    public RightShift (Distribution distribution, int shiftAmount) {
        this.distribution = distribution;
        this.shiftAmount = shiftAmount;
    }

    @Override
    public int minTime () {
        return distribution.minTime() + shiftAmount;
    }

    @Override
    public int maxTime () {
        return distribution.maxTime() + shiftAmount;
    }

    @Override
    public double maxCumulativeProbability () {
        return distribution.maxCumulativeProbability();
    }

    @Override
    public double probabilityDensity (int t) {
        return distribution.probabilityDensity(t - shiftAmount);
    }

    @Override
    public double cumulativeProbability (int t) {
        return distribution.cumulativeProbability(t - shiftAmount);
    }

    @Override
    public RightShift rightShift (int shiftAmount) {
        // Special case optimization for already-right-shifted classes: merge the two layers of shifting into one object.
        return new RightShift(distribution, this.shiftAmount + shiftAmount);
    }

}
