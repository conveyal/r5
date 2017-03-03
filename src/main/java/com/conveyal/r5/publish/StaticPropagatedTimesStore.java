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
    public void setFromArray(int[][] times, float reachabilityThreshold) {
        // NB this no longer calls the superclass method to compute averages as they're not needed for static sites
        this.times = times;
    }
}
