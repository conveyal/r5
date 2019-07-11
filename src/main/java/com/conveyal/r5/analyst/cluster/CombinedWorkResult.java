package com.conveyal.r5.analyst.cluster;

/**
 * Model class used to report travel times, paths, and accessibility indicators to the backend/broker
 */
public class CombinedWorkResult {

    public String jobId;
    public int taskId;

    /**
     * Values from a travelTimeResult, keyed on percentile and target index
     */
    public int[][] travelTimeValues;

    // TODO Paths

    /**
     * We report accessibility for a particular travel time cutoff, with travel time defined as a particular percentile.
     * So the rows are the percentiles, and the columns are the accessibility values for particular cutoffs of that percentile of travel time.
     * There are also more cutoffs than percentiles, so given Java's 2D array representation this is more efficient.
     * A particular result value should be keyed on (destinationGrid, percentile, cutoff).
     */
    public int[][][] accessibilityValues; // TODO Should this be floating point?

    /** Trivial no-arg constructor for deserialization. */
    public CombinedWorkResult() {};

    public CombinedWorkResult(int[][] travelTimeValues, int[][][] accessibilityValues ) {
        this.travelTimeValues = travelTimeValues;
        this.accessibilityValues = accessibilityValues;
    }

}
