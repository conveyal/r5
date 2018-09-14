package com.conveyal.r5.profile.entur.api;

import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.Collection;


/**
 * All input parameters to RangeRaptor that is spesific to a routing request.
 * See {@link TransitDataProvider} for transit data.
 */
public class RangeRaptorRequest {


    /** The beginning of the departure window, in seconds since midnight. */
    public final int fromTime;

    /** The end of the departure window, in seconds since midnight. */
    public final int toTime;

    /** Times to access each transit stop using the street network in seconds. */
    public final Collection<DurationToStop> accessStops;

    /** List of all possible egress stops and time to reach destination in seconds. */
    public final Collection<DurationToStop> egressStops;

    /**
     * Step for departure times between each RangeRaptor iterations.
     */
    public final int departureStepInSeconds;

    /**
     * The minimum wait time for transit boarding to account for schedule variation.
     * This is added between transits, between transfer and transit, and between access "walk" and transit.
     */
    public final int boardSlackInSeconds;


    public RangeRaptorRequest(int fromTime, int toTime, Collection<DurationToStop> accessStops, Collection<DurationToStop> egressStops, int departureStepInSeconds, int boardSlackInSeconds) {
        this.fromTime = fromTime;
        this.toTime = toTime;
        this.accessStops = accessStops;
        this.egressStops = egressStops;
        this.departureStepInSeconds = departureStepInSeconds;
        this.boardSlackInSeconds = boardSlackInSeconds;
    }

    /**
     * Compute number of minutes for scheduled search
     */
    int nMinutes() {
        return  (toTime - fromTime) / departureStepInSeconds;
    }

    @Override
    public String toString() {
        return "RangeRaptorRequest{" +
                "from=" + TimeUtils.timeToStrLong(fromTime) +
                ", toTime=" + TimeUtils.timeToStrLong(toTime) +
                ", accessStops=" + accessStops +
                ", egressStops=" + egressStops +
                ", departureStepInSeconds=" + departureStepInSeconds +
                ", boardSlackInSeconds=" + boardSlackInSeconds +
                '}';
    }
}
