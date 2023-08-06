package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.google.common.base.Preconditions;

import static com.conveyal.r5.common.Util.notNullOrEmpty;

/**
 * An instance of this is included in a OneOriginResult for reporting the nearest N destinations.
 * If we use more than one destination point set they must all be aligned with the same number of destinations.
 *
 * This supports two kinds of results... (expand from comments on https://github.com/conveyal/r5/pull/884)
 */
public class NearestNResult {

    private static final int DEFAULT_N_OPPORTUNITIES = 3;
    private final PointSet[] destinationPointSets;
    private final int nPercentiles;
    private final int nOpportunities;

    public static class NearbyOpportunity {
        public int seconds = Integer.MAX_VALUE;
        public int target;
        public String id;
    }

    // Fields to accumulate results

    /** For each percentile, the closest N destinations */
    public final NearbyOpportunity[][] nearby;

    /**
     * For each destination set, for each percentile, for each minute of travel from 0 to 120, the number of
     * opportunities reached in travel times from i (inclusive) to i+1 (exclusive).
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

    public void record (int target, int[] travelTimePercentilesSeconds) {
        // Incremenet histogram bin for the number of minutes of travel, by the number of opportunities at the target.
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
            // Find the slot with the highest travel time less than the reported travel time for this percentile.
            // TODO keep ordered by scanning from high end and shifting all elements to insert.
            int toReplace = -1;
            for (int i = 0; i < nOpportunities; i++) {
                if (nearby[p][i] == null) {
                    nearby[p][i] = new NearbyOpportunity();
                    toReplace = i;
                    break;
                }
                if (travelTimePercentilesSeconds[p] < nearby[p][i].seconds) {
                    if (toReplace < 0 || (nearby[p][i].seconds > nearby[p][toReplace].seconds)) {
                        toReplace = i;
                    }
                }
            }
            if (toReplace >= 0) {
                nearby[p][toReplace].seconds = travelTimePercentilesSeconds[p];
                nearby[p][toReplace].target = target;
                nearby[p][toReplace].id = destinationPointSets[0].getId(target);
            }
        }
    }

}
