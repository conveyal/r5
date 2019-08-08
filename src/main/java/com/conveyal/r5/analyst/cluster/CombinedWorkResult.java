package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.OneOriginContainer;

/**
 * Model class used to report travel times and accessibility indicators to the backend/broker. Similar to
 * OneOriginContainer, but with arrays for results instead of more descriptive objects
 */
public class CombinedWorkResult {

    public String jobId;
    public int taskId;

    /**
     * Values from a travelTimeResult, keyed on percentile of total travel time and target index. FIXME Note that the
     * broker's polling system was not designed to handle large amounts of data; travel time results are currently
     * an experimental feature and large numbers of targets may overwhelm the system.
     */
    public int[][] travelTimeValues;

    // TODO paths and components (access/egress, wait) of travel time?

    /**
     * We report accessibility for a particular travel time cutoff, with travel time defined as a particular percentile.
     * So the rows are the percentiles, and the columns are the accessibility values for particular cutoffs of that percentile of travel time.
     * There are also more cutoffs than percentiles, so given Java's 2D array representation this is more efficient.
     * A particular result value should be keyed on (destinationGrid, percentile, cutoff).
     */
    public int[][][] accessibilityValues; // TODO Should this be floating point?

    /** Trivial no-arg constructor for deserialization. */
    public CombinedWorkResult() {};

    public CombinedWorkResult(RegionalWorkResult result) {
        this.jobId = result.jobId;
        this.taskId = result.taskId;
        this.travelTimeValues = null;
        this.accessibilityValues = result.accessibilityValues;
    }

    public CombinedWorkResult(OneOriginContainer container) {
        this.jobId = container.jobId;
        this.taskId = container.taskId;
        this.travelTimeValues = container.travelTimes == null ? null : container.travelTimes.values;
        this.accessibilityValues = container.accessibility.getIntValues();
    }

}
