package com.conveyal.r5.transit;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

/**
 * Temporal details of a specific iteration of our RAPTOR implementation (per-leg wait times and total time
 * implied by a specific departure time and randomized schedule offsets).
 */
public class IterationTemporalDetails {
    public int departureTime;
    public TIntList waitTimes;
    public int totalTime;

    public IterationTemporalDetails(int departureTime, TIntList waitTimes, int totalTime) {
        this.departureTime = departureTime;
        this.waitTimes = waitTimes;
        this.totalTime = totalTime;
    }

    /**
     * Constructor for paths with no transit boardings (and therefore no wait times).
     */
    public IterationTemporalDetails(int totalTime) {
        this.waitTimes = new TIntArrayList();
        this.totalTime = totalTime;
    }
}
