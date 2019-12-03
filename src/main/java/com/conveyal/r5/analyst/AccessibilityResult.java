package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;

/**
 * This holds and accumulates multiple accessibility values for a single origin as they are computed.
 * The different accessibility values are for different destination point sets, travel time cutoffs, and
 * percentiles of travel time.
 *
 * This should be used internally by R5 workers; values are kept as doubles while accumulating results, and related
 * fields are included for convenience. Once accumulation is done and results are ready for use elsewhere (e.g.
 * assembling multiple results in the broker), the getIntValues method returns an array of rounded integers.
 */
public class AccessibilityResult {

    public final PointSet[] destinationPointSets;
    public final double[] percentiles;
    public final int[] cutoffs;

    private double[][][] values;

    public AccessibilityResult (AnalysisTask task) {
        this.destinationPointSets = new PointSet[] {((RegionalTask)task).destinationPointSet};
        this.percentiles = task.percentiles;
        this.cutoffs =  new int[]{task.maxTripDurationMinutes};
        values = new double[destinationPointSets.length][percentiles.length][cutoffs.length];
    }

    public AccessibilityResult () {
        this.destinationPointSets = null;
        this.percentiles = null;
        this.cutoffs = null;
        this.values = new double[1][1][1];
    }


    /**
     * Increment the accessibility indicator value for the given grid, cutoff, and percentile
     * by the given number of opportunities. This is called repeatedly to accumulate reachable
     * destinations into different indicator values.
     */
    public void incrementAccessibility (int gridIndex, int percentileIndex, int cutoffIndex, double amount) {
        values[gridIndex][percentileIndex][cutoffIndex] += amount;
    }

    public int[][][] getIntValues () {
        int[][][] result = new int[destinationPointSets.length][percentiles.length][cutoffs.length];
        for (int i = 0; i < values.length; i++) {
            double[][] gridResult = values[i];
            for (int j = 0; j < gridResult.length; j++) {
                double[] cutoffResult = gridResult[j];
                for (int k = 0; k < cutoffResult.length; k++) {
                    result[i][j][k] = (int) Math.round(values[i][j][k]);
                }
            }
        }
        return  result;
    }
}
