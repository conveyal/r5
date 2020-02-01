package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisTask;

/**
 * This holds and accumulates multiple accessibility indicator values for a single origin as they are computed.
 * The different accessibility indicator values are for different opportunity PointSets, different percentiles of travel
 * time, and different travel time cutoffs.
 *
 * This should only be used internally by R5 workers. Values are kept as doubles while accumulating results.
 * Once accumulation is done and results are ready for use elsewhere (e.g. assembling multiple results in the broker)
 * the getIntValues method returns an array of rounded integers.
 */
public class AccessibilityResult {

    private final int nPointSets;
    private final int nPercentiles;
    private final int nCutoffs;

    private final double[][][] cumulativeOpportunities;

    /** Construct an AccessibilityResult of the appropriate dimensions for the specified AnalysisTask. */
    public AccessibilityResult (AnalysisTask task) {
        this.nPointSets = 1;
        this.nPercentiles = task.percentiles.length;
        this.nCutoffs = task.cutoffsMinutes.length;
        cumulativeOpportunities = new double[nPointSets][nPercentiles][nCutoffs];
    }

    /** Constructor for empty results, for use in testing only. */
    public AccessibilityResult () {
        this.nPointSets = 0;
        this.nPercentiles = 0;
        this.nCutoffs = 0;
        this.cumulativeOpportunities = new double[0][0][0];
    }

    /**
     * Increment the accessibility indicator value for the given grid, cutoff, and percentile
     * by the given number of opportunities. This is called repeatedly to accumulate reachable
     * destinations into different indicator values.
     */
    public void incrementAccessibility (int gridIndex, int percentileIndex, int cutoffIndex, double amount) {
        cumulativeOpportunities[gridIndex][percentileIndex][cutoffIndex] += amount;
    }

    /**
     * As travel time cutoff increases, accessibility should increase.
     * As percentile increases, travel time should decrease, and accessibility should decrease.
     * If one of these invariants does not hold, there is something wrong with the calculations.
     */
    private void checkInvariants () {
        for (int d = 0; d < nPointSets; d++) {
            for (int p = 0; p < nPercentiles; p++) {
                for (int c = 0; c < nCutoffs; c++) {
                    if (c > 0 && cumulativeOpportunities[d][p][c] < cumulativeOpportunities[d][p][c - 1]) {
                        throw new AssertionError("Increasing travel time decreased accessibility.");
                    }
                    if (p > 0 && cumulativeOpportunities[d][p][c] > cumulativeOpportunities[d][p - 1][c]) {
                        throw new AssertionError("Increasing percentile increased accessibility.");
                    }
                }
            }
        }
    }

    /**
     * Opportunity counts may be fractional because they were disaggregated from polygons, or because a weighting or
     * rolloff function was applied to them. After accumulating many such potentially fractional opportunity counts,
     * we round them off to whole numbers for reporting as final results.
     */
    public int[][][] getIntValues () {
        checkInvariants();
        int[][][] intAccessibility = new int[nPointSets][nPercentiles][nCutoffs];
        for (int d = 0; d < nPointSets; d++) {
            for (int p = 0; p < nPercentiles; p++) {
                for (int c = 0; c < nCutoffs; c++) {
                    intAccessibility[d][p][c] = (int) Math.round(cumulativeOpportunities[d][p][c]);
                }
            }
        }
        return  intAccessibility;
    }
}
