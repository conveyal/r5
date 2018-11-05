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
    public final Collection<StopArrival> accessStops;

    /**
     * List of all possible egress stops and time to reach destination in seconds.
     * <p>
     * NOTE! The {@link StopArrival#stop()} is the stop where the egress leg
     * start, NOT the destination - think of it as a reversed leg.
     */
    public final Collection<StopArrival> egressStops;

    /**
     * Step for departure times between each RangeRaptor iterations.
     * This is a performance optimization parameter.
     * A transit network usually uses minute resolution for the its timetable,
     * so to match that set this variable to 60 seconds. Setting it
     * to less then 60 will not give better result, but degrade performance.
     * Setting it to 120 seconds will improve performance, but you might get a
     * slack of 60 seconds somewhere in the result - most likely in the first
     * walking leg.
     */
    public final int departureStepInSeconds;

    /**
     * The minimum wait time for transit boarding to account for schedule variation.
     * This is added between transits, between transfer and transit, and between access "walk" and transit.
     */
    public final int boardSlackInSeconds;


    public RangeRaptorRequest(int fromTime, int toTime, Collection<StopArrival> accessStops, Collection<StopArrival> egressStops, int departureStepInSeconds, int boardSlackInSeconds) {
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
