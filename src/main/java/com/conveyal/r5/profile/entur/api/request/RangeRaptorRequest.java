package com.conveyal.r5.profile.entur.api.request;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.ArrayList;
import java.util.Collection;


/**
 * All input parameters to RangeRaptor that is spesific to a routing request.
 * See {@link TransitDataProvider} for transit data.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public interface RangeRaptorRequest<T extends TripScheduleInfo> {
    /**
     * The profile/algorithm to use for this request.
     * <p/>
     * The default value is {@link RaptorProfiles#MULTI_CRITERIA_RANGE_RAPTOR}
     */
    default RaptorProfiles profile() {
        return RaptorProfiles.MULTI_CRITERIA_RANGE_RAPTOR;
    }

    /**
     * The beginning of the departure window, in seconds since midnight. Inclusive.
     * <p/>
     * Required. Must be a positive integer, seconds since midnight(transit service time).
     */
    int fromTime();

    /**
     * The end of the departure window, in seconds since midnight. Exclusive.
     * <p/>
     * Required. Must be a positive integer, seconds since midnight(transit service time).
     */
    int toTime();

    /**
     * Times to access each transit stop using the street network in seconds.
     * <p/>
     * Required, at least one access leg must exist.
     */
    default Collection<TransferLeg> accessLegs() {
        return new ArrayList<>();
    }

    /**
     * List of all possible egress stops and time to reach destination in seconds.
     * <p>
     * NOTE! The {@link TransferLeg#stop()} is the stop where the egress leg
     * start, NOT the destination - think of it as a reversed leg.
     * <p/>
     * Required, at least one egress leg must exist.
     */
    default Collection<TransferLeg> egressLegs() {
        return new ArrayList<>();
    }

    /**
     * The minimum wait time for transit boarding to account for schedule variation.
     * This is added between transits, between transfer and transit, and between access "walk" and transit.
     * <p/>
     * The default value is 60.
     */
    default int boardSlackInSeconds() {
        return 60;
    }


    /**
     * RangeRaptor is designed to search until the destination is reached and then
     * {@code numberOfAdditionalTransfers} more rounds.
     * <p/>
     * The default value is 3.
     */
    default int numberOfAdditionalTransfers() {
        return 3;
    }

    /**
     * Specify what to debug in the debug request.
     * <p/>
     * This feature is optional, by default debugging is turned off.
     */
    default DebugRequest<T> debug() {
        return null;
    }
}
