package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
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

    /**
     * Writes dual access travel time values (in minutes) to our standard access grid format. The value returned (for
     * an origin) is the number of minutes required to reach a threshold number of opportunities (specified by
     * the cutoffs and task.dualAccessibilityThreshold) in the specified destination layer at a given percentile of
     * travel time. If the threshold cannot be reached in less than 120 minutes, returns 0.
     * This is a temporary experimental feature, (ab)using existing features in the UI and backend so that dual access
     * results for grid origins can be obtained without any changes to those other components of our system. It uses
     * the supplied task.cutoffsMinutes as dual access thresholds. If a nonzero task.dualAccessibility In place of the
     * last
     * cutoffsMinutes value, it uses task.dualAccessibilityThreshold (which is initialized to 0, so this is safe even
     * if a user does not supply it).
     */
    public int[][][] fakeDualAccess (RegionalTask task) {
        int nPointSets = task.destinationPointSets.length;
        int nCutoffs = task.cutoffsMinutes.length;
        int[][][] dualAccess = new int[nPointSets][nPercentiles][nCutoffs];
        for (int d = 0; d < nPointSets; d++) {
            for (int p = 0; p < nPercentiles; p++) {
                // Hack: use cutoffs as dual access thresholds
                for (int c = 0; c < nCutoffs; c++) {
                    int m = 0;
                    double sum = 0;
                    while (sum < task.cutoffsMinutes[c] && m < 120) {
                        sum += opportunitiesPerMinute[d][p][m];
                        m += 1;
                    }
                    dualAccess[d][p][c] = m;
                }
                // But the hack above won't allow thresholds over 120 (see validateCutoffsMinutes()); so overwrite
                // the value for the last cutoff if a nonzero task.dualAccessibilityThreshold value is supplied.
                if (task.dualAccessibilityThreshold != 0) {
                    int m = 0;
                    double sum = 0;
                    while (sum < task.dualAccessibilityThreshold && m < 120) {
                        sum += opportunitiesPerMinute[d][p][m];
                        m += 1;
                    }
                    dualAccess[d][p][nCutoffs - 1] = m;
                }
            }
        }
        return dualAccess;
    }

    public int[][] minutesToReachOpportunities() {
        return minutesToReachOpportunities(opportunityThreshold);
    }

}
