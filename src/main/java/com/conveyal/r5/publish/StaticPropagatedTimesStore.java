package com.conveyal.r5.publish;

import com.conveyal.r5.profile.PropagatedTimesStore;

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
    public void setFromArray(int[][] times, ConfidenceCalculationMethod confidenceCalculationMethod) {
        super.setFromArray(times, confidenceCalculationMethod);
        this.times = times;
    }
}
