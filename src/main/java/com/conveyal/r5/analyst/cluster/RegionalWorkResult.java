package com.conveyal.r5.analyst.cluster;

/**
 * Was used to report accessibility indicators to the backend/broker; replaced by {@link CombinedWorkResult}
 * We report accessibility for a particular travel time cutoff, with travel time defined as a particular percentile.
 * So the rows are the percentiles, and the columns are the accessibility values for particular cutoffs of that percentile of travel time.
 * There are also more cutoffs than percentiles, so given Java's 2D array representation this is more efficient.
 * A particular result value should be keyed on (destinationGrid, percentile, cutoff).
 */
@Deprecated
public class RegionalWorkResult {

    public String jobId;
    public int taskId;
    public int[][][] accessibilityValues; // TODO Should this be floating point?

    // TODO add a way to signal that an error occurred when processing this task.
    // public String errors;
    // List all grids, percentiles, and travel time cutoffs? That should be in the job itself.

    /** Trivial no-arg constructor for deserialization. */
    public RegionalWorkResult () {};

    // TODO constructor that takes a request and extracts the necessary numbers
    public RegionalWorkResult(String jobId, int taskId, int nGrids, int nPercentiles, int nTravelTimeCutoffs) {
        this.jobId = jobId;
        this.taskId = taskId;
        // The array values will default to zero, which is what we want for accessibility
        this.accessibilityValues = new int[nGrids][nPercentiles][nTravelTimeCutoffs];
    }

    public void setAcccessibilityValue (int gridIndex, int percentileIndex, int cutoffMinutesIndex, int value) {
        accessibilityValues[gridIndex][percentileIndex][cutoffMinutesIndex] = value;
    }

    /**
     * Wrap this old-style result into a new result.  This updated method won't be present on old workers, but it
     * will be available for use in the Broker via the R5 dependency.
     * @return new CombinedWorkResult with this result's accessibility values and null travelTimeValues
     */
    public CombinedWorkResult toCombinedResult() {
        return new CombinedWorkResult(this);
    }

}
