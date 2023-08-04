package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;

/**
 * An instance of this is included in a OneOriginResult for reporting the nearest N destinations.
 * If we use more than one destination point set they must all be aligned with the same number of destinations.
 */
public class NearestNResult {

    private final PointSet[] destinationPointSets;
    private final int nPercentiles;
    private final int nOpportunities;

    public static class NearbyOpportunity {
        public int seconds = Integer.MAX_VALUE;
        public int target;
        public String id;
    }

    // For each percentile, the closest N destinations
    public final NearbyOpportunity[][] opportunities;

    public NearestNResult (AnalysisWorkerTask task) {
        this.destinationPointSets = task.destinationPointSets;
        this.nPercentiles = task.percentiles.length;
        this.nOpportunities = 3;
        this.opportunities = new NearbyOpportunity[nPercentiles][nOpportunities];
    }

    public void record (int target, int[] travelTimePercentilesSeconds) {
        for (int p = 0; p < nPercentiles; p++) {
            // Find the slot with the highest travel time less than the reported travel time for this percentile.
            int toReplace = -1;
            for (int i = 0; i < nOpportunities; i++) {
                if (opportunities[p][i] == null) {
                    opportunities[p][i] = new NearbyOpportunity();
                    toReplace = i;
                    break;
                }
                if (travelTimePercentilesSeconds[p] < opportunities[p][i].seconds) {
                    if (toReplace < 0 || (opportunities[p][i].seconds > opportunities[p][toReplace].seconds)) {
                        toReplace = i;
                    }
                }
            }
            if (toReplace >= 0) {
                opportunities[p][toReplace].seconds = travelTimePercentilesSeconds[p];
                opportunities[p][toReplace].target = target;
                // There are actually as many IDs as there are destination point sets but record only the first for now.
                // However they are only set in regional analyses.
                if (destinationPointSets != null) {
                    opportunities[p][toReplace].id = destinationPointSets[0].getId(target);
                }
            }
        }
    }


}
