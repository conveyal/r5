package com.conveyal.r5.profile.entur.api.request;

import com.conveyal.r5.profile.entur.api.transit.AccessLeg;
import com.conveyal.r5.profile.entur.api.transit.EgressLeg;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.Collection;
import java.util.Collections;


/**
 * All input parameters to RangeRaptor that is spesific to a routing request.
 * See {@link TransitDataProvider} for transit data.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RangeRaptorRequest<T extends TripScheduleInfo> {

    /**
     * Default values
     * @see #RangeRaptorRequest() for property default values.
     */
    static final RangeRaptorRequest DEFAULTS = new RangeRaptorRequest();

    private static final int NOT_SET = -1;


    /** The profile/algorithm to use for this request. */
    public final RaptorProfiles profile;

    /** The beginning of the departure window, in seconds since midnight. Inclusive. */
    public final int fromTime;

    /** The end of the departure window, in seconds since midnight. Exclusive. */
    public final int toTime;

    /** Times to access each transit stop using the street network in seconds. */
    public final Collection<AccessLeg> accessLegs;

    /**
     * List of all possible egress stops and time to reach destination in seconds.
     * <p>
     * NOTE! The {@link EgressLeg#stop()} is the stop where the egress leg
     * start, NOT the destination - think of it as a reversed leg.
     */
    public final Collection<EgressLeg> egressLegs;

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


    /**
     * RangeRaptor is designed to search until the destination is reached and then
     * {@code numberOfAdditionalTransfers} more rounds.
     *
     * TODO TGR - Implement this feature.
     */
    public final int numberOfAdditionalTransfers;

    /**
     * Specify what to debug in the debug request.
     * This feature is optonal.
     */
    public final DebugRequest<T> debug;

    /**
     * Intentionally private default constructor, which only serve as a place
     * to define default values.
     */
    private RangeRaptorRequest() {
        // Required parameters
        this.fromTime = NOT_SET;
        this.toTime = NOT_SET;
        this.accessLegs = Collections.emptyList();
        this.egressLegs = Collections.emptyList();

        // Optional parameters with default values
        this.profile = RaptorProfiles.MULTI_CRITERIA_RANGE_RAPTOR;
        this.departureStepInSeconds = 60;
        this.boardSlackInSeconds = 60;
        this.numberOfAdditionalTransfers = 3;
        this.debug = null;
    }

    RangeRaptorRequest(RequestBuilder<T> builder) {
        this.profile = builder.profile;
        this.fromTime = builder.fromTime;
        this.toTime = builder.toTime;
        this.accessLegs = builder.accessLegs;
        this.egressLegs = builder.egressLegs;
        this.departureStepInSeconds = builder.departureStepInSeconds;
        this.boardSlackInSeconds = builder.boardSlackInSeconds;
        this.numberOfAdditionalTransfers = builder.numberOfAdditionalTransfers;
        this.debug = new DebugRequest<>(builder);
    }

    @Override
    public String toString() {
        return "RangeRaptorRequest{" +
                "from=" + TimeUtils.timeToStrLong(fromTime) +
                ", toTime=" + TimeUtils.timeToStrLong(toTime) +
                ", accessLegs=" + accessLegs +
                ", egressLegs=" + egressLegs +
                ", departureStepInSeconds=" + departureStepInSeconds +
                ", boardSlackInSeconds=" + boardSlackInSeconds +
                '}';
    }
}
