package com.conveyal.r5.transit;

import java.util.Arrays;

/**
 * Timestamp style clock times, and rounded wait/total time, for inspection as JSON.
 */
public class HumanReadableTemporalIterationDetails {
    public String departureTime;
    public double[] waitTimes;
    public double totalTime;

    public HumanReadableTemporalIterationDetails(IterationTemporalDetails iteration) {
        // TODO track departure time for non-transit paths (so direct trips don't show departure time 00:00).
        this.departureTime =
                String.format("%02d:%02d", Math.floorDiv(iteration.departureTime, 3600),
                        (int) (iteration.departureTime / 60.0 % 60));
        this.waitTimes = Arrays.stream(iteration.waitTimes.toArray()).mapToDouble(
                wait -> Math.round(wait / 60f * 10) / 10.0
        ).toArray();
        this.totalTime = Math.round(iteration.totalTime / 60f * 10) / 10.0;
    }
}
