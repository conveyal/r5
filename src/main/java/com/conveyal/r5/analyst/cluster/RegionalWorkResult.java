package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.OneOriginResult;

/**
 * Model class used for serialized travel times and accessibility indicators for a multi-origin (regional) analysis,
 * sent over HTTP to the backend/broker. Similar to OneOriginResult, but with arrays for results (instead of more
 * descriptive objects) for compactness.
 */
public class RegionalWorkResult {

    public String jobId;
    public int taskId;

    /**
     * Values from a travelTimeResult, keyed on percentile of total travel time and target index.
     * FIXME Note that the broker's polling system was not designed to handle large amounts of data; travel time
     *       results are currently an experimental feature and large numbers of targets may overwhelm the system.
     */
    public int[][] travelTimeValues;

    // TODO paths and components (access/egress, wait) of travel time?

    /**
     * We report accessibility for a particular travel time cutoff, with travel time defined as a particular percentile.
     * So the rows are the percentiles, and the columns are the accessibility values for particular cutoffs of that percentile of travel time.
     * There are also more cutoffs than percentiles, so given Java's 2D array representation this is more efficient.
     * These are the truncated integer accessibility results for each [destinationGrid, percentile, cutoff].
     */
    public int[][][] accessibilityValues; // TODO Should this be floating point?

    /** Trivial no-arg constructor for deserialization. Private to prevent usage outside deserialization. */
    private RegionalWorkResult() { }

    /**
     * Convert the supplied internal R5 OneOriginResult into this more compact form intended for serialization
     * and transfer from the worker back to the backend. The job and task ID are copied from the supplied task
     * to show which task these results are for.
     */
    public RegionalWorkResult(OneOriginResult result, RegionalTask task) {
        this.jobId = task.jobId;
        this.taskId = task.taskId;
        this.travelTimeValues = result.travelTimes == null ? null : result.travelTimes.values;
        this.accessibilityValues = result.accessibility == null ? null : result.accessibility.getIntValues();
    }

    // TODO checkTravelTimeInvariants, checkAccessibilityInvariants

}
