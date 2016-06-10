package com.conveyal.r5.publish;

import com.conveyal.r5.profile.PropagatedTimesStore;

import java.util.BitSet;

/**
 * A PropagatedTimesStore for storing travel times to transit stops.
 */
public class StaticPropagatedTimesStore extends PropagatedTimesStore {
    /** This is the times at each iteration for each stop, with iterations in the outer array */
    public int[][] times;

    public StaticPropagatedTimesStore (int stopCount) {
        super(stopCount);
    }

    @Override
    public void setFromArray(int[][] times, BitSet includeInAverages, ConfidenceCalculationMethod confidenceCalculationMethod, float reachabilityThreshold) {
        super.setFromArray(times, includeInAverages, confidenceCalculationMethod, reachabilityThreshold);

        // don't include extrema in the times we save.
        this.times = new int[includeInAverages.cardinality()][];

        for (int i = 0, pos = 0; i < times.length; i++) {
            if (includeInAverages.get(i)) this.times[pos++] = times[i];
        }
    }
}
