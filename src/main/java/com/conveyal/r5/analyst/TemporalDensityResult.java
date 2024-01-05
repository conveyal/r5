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
 * The data retained here feed into three different kinds of results: "Dual" accessibility (the number of opportunities
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

    // Internal state fields

    private final PointSet[] destinationPointSets;
    private final int nPercentiles;
    private final int opportunityThreshold;

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
            "Dual accessibility requires at least one destination pointset."
        );
        this.destinationPointSets = task.destinationPointSets;
        this.nPercentiles = task.percentiles.length;
        this.opportunityThreshold = task.dualAccessibilityThreshold;
        this.opportunitiesPerMinute = new double[destinationPointSets.length][nPercentiles][120];
    }

    public void recordOneTarget (int target, int[] travelTimePercentilesSeconds) {
        // Increment histogram bin for the number of minutes of travel by the number of opportunities at the target.
        for (int d = 0; d < destinationPointSets.length; d++) {
            PointSet dps = destinationPointSets[d];
            for (int p = 0; p < nPercentiles; p++) {
                if (travelTimePercentilesSeconds[p] == UNREACHED) {
                    break; // If any percentile is unreached, all higher ones are also unreached.
                }
                int m = travelTimePercentilesSeconds[p] / 60;
                if (m < 120) {
                    opportunitiesPerMinute[d][p][m] += dps.getOpportunityCount(target);
                }
            }
        }
    }

    /**
     * Calculate "dual" accessibility from the accumulated temporal opportunity density array.
     * @param n the threshold quantity of opportunities
     * @return the minimum whole number of minutes necessary to reach n opportunities,
     *         for each destination set and percentile of travel time.
     */
    public int[][] minutesToReachOpportunities(int n) {
        int[][] result = new int[destinationPointSets.length][nPercentiles];
        for (int d = 0; d < destinationPointSets.length; d++) {
            for (int p = 0; p < nPercentiles; p++) {
                result[d][p] = -1;
                double count = 0;
                for (int m = 0; m < 120; m++) {
                    count += opportunitiesPerMinute[d][p][m];
                    if (count >= n) {
                        result[d][p] = m + 1;
                        break;
                    }
                }
            }
        }
        return result;
    }

    public int[][] minutesToReachOpportunities() {
        return minutesToReachOpportunities(opportunityThreshold);
    }

}
