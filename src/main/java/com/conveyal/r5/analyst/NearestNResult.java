package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.google.common.base.Preconditions;

import static com.conveyal.r5.common.Util.newObjectArray;
import static com.conveyal.r5.common.Util.notNullOrEmpty;

/**
 * An instance of this is included in a OneOriginResult for reporting the nearest N destinations. If we use more than
 * one destination point set they are already constrained to all be aligned with the same number of destinations.
 *
 * The data retained here feed into three different kinds of results: "Dual" accessibility (the number of opportunities
 * reached in a given number of minutes of travel time); temporal opportunity density (akin to a probability density
 * function, how many opportunities are encountered during each minute of travel, whose integral is the cumulative
 * accessibility curve); and the nearest one or more opportunities to a given origin.
 * (expand from comments on https://github.com/conveyal/r5/pull/884)
 */
public class NearestNResult {

    private static final int DEFAULT_N_OPPORTUNITIES = 3;
    private final PointSet[] destinationPointSets;
    private final int nPercentiles;
    private final int nOpportunities;

    /**
     * Candidate for record instead of class with newer Java version.
     */
    public static class NearbyOpportunity {
        public int seconds = Integer.MAX_VALUE;
        public int target;
        public String id;

        @Override
        public String toString() {
            return "NearbyOpportunity{" +
                    "seconds=" + seconds +
                    ", target=" + target +
                    ", id='" + id + '\'' +
                    '}';
        }
    }

    // Fields to accumulate results

    /** For each percentile, the closest N destinations with their IDs and how long it takes to reach them. */
    public final NearbyOpportunity[][] nearby;

    /**
     * The temporal density of opportunities. For each destination set, for each percentile, for each minute of
     * travel from 0 to 120, the number of opportunities reached in travel times from i (inclusive) to i+1 (exclusive).
     */
    public final double[][][] opportunitiesPerMinute;

    public NearestNResult (AnalysisWorkerTask task) {
        Preconditions.checkArgument(
            notNullOrEmpty(task.destinationPointSets),
            "Dual accessibility requires at least one destination pointset."
        );
        this.destinationPointSets = task.destinationPointSets;
        this.nPercentiles = task.percentiles.length;
        this.nOpportunities = DEFAULT_N_OPPORTUNITIES;
        this.nearby = new NearbyOpportunity[nPercentiles][nOpportunities];
        this.opportunitiesPerMinute = new double[destinationPointSets.length][nPercentiles][120];
    }

    private int listLength = 0; // increment as lists grow in length; use as initial insert position

    public void record (int target, int[] travelTimePercentilesSeconds) {
        // Increment histogram bin for the number of minutes of travel by the number of opportunities at the target.
        for (int d = 0; d < destinationPointSets.length; d++) {
            PointSet dps = destinationPointSets[d];
            for (int p = 0; p < nPercentiles; p++) {
                int i = travelTimePercentilesSeconds[p] / 60;
                if (i <= 120) {
                    opportunitiesPerMinute[d][p][i] += dps.getOpportunityCount(target);
                }
            }
        }
        // Insert the destination in the list of nearest destinations if it improves on those already present.
        // Nearest N only makes sense for freeform point sets that define IDs for each point. If there were
        // multiple destination point sets, each of them could define a different ID for the same point.
        // However unlike gridded pointsets, we don't allow more than one freeform pointset in the same request.
        // Note also that in single-point analyses, the destination pointsets may be null if the user has not
        // selected any pointset in the UI (or if the step function is selected rather than another decay function).
        // So if any destinations are present, we only care about the first set.
        for (int p = 0; p < nPercentiles; p++) {
            final int t = travelTimePercentilesSeconds[p]; // shorthand for current travel time
            int i = listLength; // insertion point in list
            // Shift elements to the right, dropping one off end of list, until insertion position is discovered.
            while (i > 0 && nearby[p][i - 1] != null && t < nearby[p][i - 1].seconds) {
                if (i < nOpportunities) {
                    nearby[p][i] = nearby[p][i - 1];
                }
                i -= 1;
            }
            if (i < nOpportunities) {
                final NearbyOpportunity tr = new NearbyOpportunity();
                tr.seconds = travelTimePercentilesSeconds[p];
                tr.target = target;
                tr.id = destinationPointSets[0].getId(target);
                nearby[p][i] = tr;
            }
        }
        // If the lists in the arrays had not yet reached their full length, they will have grown by one.
        if (listLength < nOpportunities) {
            listLength += 1;
        }
    }

    /**
     * Calculate "dual" accessibility from the accumulated temporal opportunity density array.
     * @param n the threshold quantity of opportunities
     * @return the number of minutes it takes to reach n opportunities, for each destination set and percentile of travel time.
     */
    public int[][] minutesToReachOpporunities (int n) {
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

}
