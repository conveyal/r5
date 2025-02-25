package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.google.common.base.Preconditions;

import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;

/**
 * An instance of this is included in a OneOriginResult for reporting how many opportunities are encountered during each
 * minute of travel. If we use more than one destination point set they are already constrained to all be aligned with
 * the same number of destinations.
 *
 * The data retained here feed into two different kinds of results: "Dual" accessibility (the number of opportunities
 * reached in a given number of minutes of travel time); temporal opportunity density (analogous to a probability density
 * function, how many opportunities are encountered during each minute of travel, whose integral is the cumulative
 * accessibility curve).
 *
 * Originally this class was tracking the identity of the N nearest points rather than just binning them by travel time.
 * This is more efficient in cases where N is small, and allows retaining the one-second resolution. However currently
 * there does not seem to be much demand among users for this level of detail, so it has been removed in the interest
 * of simplicity and maintainability. See issue 884 for more comments on implementation trade-offs.
 */
public class TemporalDensityResult {
    public static final int TIME_LIMIT = 120;

    // Internal state fields

    private final int nPointSets;
    private final int nPercentiles;
    private final PointSet[] destinationPointSets;

    // Externally visible fields for accumulating results

    /**
     * The temporal density of opportunities. For each destination set, for each percentile, for each minute of
     * travel m from 0 to 119, the number of opportunities reached in travel times from m (inclusive) to m+1
     * (exclusive).
     */
    public final double[][][] opportunitiesPerMinute;

    public TemporalDensityResult(AnalysisWorkerTask task) {
        Preconditions.checkArgument(
            notNullOrEmpty(task.destinationPointSets),
                "Temporal density requires at least one destination pointset."
        );
        this.destinationPointSets = task.destinationPointSets;
        this.nPercentiles = task.percentiles.length;
        this.nPointSets = this.destinationPointSets.length;
        opportunitiesPerMinute = new double[this.nPointSets][this.nPercentiles][TIME_LIMIT];
    }

    public void recordOneTarget (int target, int[] travelTimePercentilesSeconds) {
        // Increment histogram bin for the number of minutes of travel by the number of opportunities at the target.
        for (int i = 0; i < nPointSets; i++) {
            PointSet dps = destinationPointSets[i];
            for (int j = 0; j < nPercentiles; j++) {
                if (travelTimePercentilesSeconds[j] == UNREACHED) {
                    break; // If any percentile is unreached, all higher ones are also unreached.
                }
                int minutes = travelTimePercentilesSeconds[j] / 60;
                if (minutes < TIME_LIMIT) {
                    opportunitiesPerMinute[i][j][minutes] += dps.getOpportunityCount(target);
                }
            }
        }
    }

    /**
     * Ensure that results have increasing accessibility while travel time increases and percentile decreases.
     */
    private static void checkInvariants(double[][][] opportunitiesPerMinute) {
        int nPointSets = opportunitiesPerMinute.length;
        int nPercentiles = opportunitiesPerMinute.length > 0 ? opportunitiesPerMinute[0].length : 0;
        double[][][] sums = new double[nPointSets][nPercentiles][TIME_LIMIT];
        for (int i = 0; i < nPointSets; i++) {
            for (int j = 0; j < nPercentiles; j++) {
                double totalSum = 0;
                for (int m = 0; m < TIME_LIMIT; m++) {
                    totalSum += opportunitiesPerMinute[i][j][m];
                    sums[i][j][m] = totalSum;

                    if (m > 0 && sums[i][j][m] < sums[i][j][m - 1]) {
                        throw new AssertionError("Increasing travel time decreased accessibility.");
                    }
                    if (j > 0 && sums[i][j][m] > sums[i][j - 1][m]) {
                        throw new AssertionError("Increasing percentile increased accessibility.");
                    }
                }
            }
        }
    }

    /**
     * Calculate dual access values (in minutes) for an origin. The values are the number of minutes required to reach 
     * a threshold number of opportunities (specified by task.dualAccessThresholds) in the specified destination layer 
     * at a given percentile of travel time. If the threshold cannot be reached in less than 120 minutes, sets the value to 0.
     */
    public int[][][] calculateDualAccessForOrigin(int[] dualAccessThresholds) {
        checkInvariants(opportunitiesPerMinute);

        int nPointSets = opportunitiesPerMinute.length;
        int nPercentiles = opportunitiesPerMinute.length > 0 ? opportunitiesPerMinute[0].length : 0;
        int nThresholds = dualAccessThresholds.length;
        int[][][] dualAccessForOrigin = new int[nPointSets][nPercentiles][nThresholds];
        for (int i = 0; i < nPointSets; i++) {
            for (int j = 0; j < nPercentiles; j++) {
                for (int k = 0; k < nThresholds; k++) {
                    dualAccessForOrigin[i][j][k] = calculateDualAccessForThreshold(opportunitiesPerMinute[i][j], dualAccessThresholds[k]);
                }
            }
        }

        return dualAccessForOrigin;
    }

    /**
     * Calculates the dual access value for an origin (in minutes) for a specific destination, percentile, and threshold.
     * If the threshold cannot be reached in less than TIME_LIMIT minutes, returns 0.
     */
    public static int calculateDualAccessForThreshold(double[] opportunitiesPerMinute, int threshold) {
        int minutes = 0;
        double sum = 0;
        for (minutes = 0; minutes < opportunitiesPerMinute.length && sum < threshold; minutes++) {
            sum += opportunitiesPerMinute[minutes];
        }
        
        if (sum < threshold) {
            return 0;
        } else {
            return minutes;
        }
    }
}
