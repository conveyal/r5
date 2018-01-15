package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.Origin;
import com.conveyal.r5.analyst.cluster.RegionalTask;

/**
 * This holds and accumulates multiple accessibility values as they are computed.
 * Created by abyrd on 2018-01-11
 * TODO rename to something less generic
 */
public class AccessibilityResult {

    public final Grid[] grids;
    public final int[] cutoffs;
    public final double[] percentiles;

    private double[][][] values;

    public AccessibilityResult (Grid[] grids, int[] cutoffs, double[] percentiles) {
        this.grids = grids;
        this.cutoffs = cutoffs;
        this.percentiles = percentiles;
        values = new double[grids.length][cutoffs.length][percentiles.length];
    }

    /**
     * Increment the accessibility indicator value for the given grid, cutoff, and percentile
     * by the given number of opportunities. This is called repeatedly to accumulate reachable
     * destinations into different indicator values.
     */
    public void incrementAccessibility (int gridIndex, int cutoffIndex, int percentileIndex, double amount) {
        values[gridIndex][cutoffIndex][percentileIndex] += amount;
    }

    public double getAccessibility(int gridIndex, int cutoffIndex, int percentileIndex) {
        return values[gridIndex][cutoffIndex][percentileIndex];
    }

}
