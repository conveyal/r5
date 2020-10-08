package com.conveyal.r5.analyst.decay;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * It's debatable whether the precomputation of a lookup table will speed up calculations. The tables get pretty big and
 * calculation may be faster than memory churn. We should try to measure with and without that optimization.
 */
public class LinearDecayFunction extends DecayFunction {

    /** The public parameter. */
    public int widthMinutes;

    /** Derived from minutes, so known to be an even number of seconds. */
    private int widthSeconds;

    /** Width in seconds is known to be even, so can be split into integer halves. */
    private int halfWidthSeconds;

    /** The weights for all the seconds along the downward slope from 1 to 0. */
    private double[] weightTable;

    @Override
    public int reachesZeroAt (int cutoffSeconds) {
        return cutoffSeconds + halfWidthSeconds;
    }

    @Override
    public void prepare () {
        // Validate
        checkArgument(widthMinutes >= 0, "Linear decay width parameter must be non-negative.");
        checkArgument(widthMinutes < 60, "Linear decay width parameter must be under one hour.");
        // Precompute
        widthSeconds = widthMinutes * 60;
        halfWidthSeconds = widthSeconds / 2;
        weightTable = new double[widthSeconds];
        for (int s = 0; s < widthSeconds; s++) {
            // All opportunities at second s are on average halfway between s and s+1 due to int truncation.
            weightTable[s] = 1 - ((s + 0.5) / widthSeconds);
        }
    }

    @Override
    public double computeWeight (int cutoffSeconds, int travelTimeSeconds) {
        int decayBegins = cutoffSeconds - halfWidthSeconds;
        int tableIndex = travelTimeSeconds - decayBegins;
        if (tableIndex < 0) {
            return 1;
        } else if (tableIndex < widthSeconds) {
            return weightTable[tableIndex];
        } else {
            return 0;
        }
    }

}
