package com.conveyal.r5.api.util;

/**
 * num may be 0 if there are no observations.
 * num will become 1 when adding a scalar or another Stats.
 */
public class Stats implements Cloneable {

    /**
     * Minimum travel time (seconds)
     * @notnull
     */
    public int min = Integer.MAX_VALUE;

    /**
     * Average travel time (including waiting) (seconds)
     * @notnull
     */
    public int avg = 0;

    /**
     * Maximum travel time (seconds)
     * @notnull
     */
    public int max = 0;

    /**
     * number of options
     * @notnull
     */
    public int num = 0;

    /** Construct a new empty Stats containing no values. */
    public Stats () { }

    @Override
    public String toString() {
        return String.format("min=%.1f avg=%.1f max=%.1f", min/60.0, avg/60.0, max/60.0);
    }
}
