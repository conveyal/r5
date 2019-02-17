package com.conveyal.r5.profile.entur.api.request;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;


/**
 * All input parameters to RangeRaptor that is spesific to a routing request.
 * See {@link TransitDataProvider} for transit data.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RangeRaptorRequest<T extends TripScheduleInfo> {
    private static final int NOT_SET = -1;
    // 60 minutes
    private static final int DEFAULT_SEARCH_WINDOW_1_HOUR_IN_SECONDS = 60 * 60;


    private final RaptorProfile profile;
    private final int earliestDepartureTime;
    private final int latestArrivalTime;
    private final int searchWindowInSeconds;
    private final boolean arrivedBy;

    private final Collection<TransferLeg> accessLegs;
    private final Collection<TransferLeg> egressLegs;
    private final int boardSlackInSeconds;
    private final int numberOfAdditionalTransfers;
    private final MultiCriteriaCostFactors multiCriteriaCostFactors;
    private final DebugRequest<T> debug;


    static <T extends TripScheduleInfo>  RangeRaptorRequest<T> defaults() {
        return new RangeRaptorRequest<>();
    }

    private RangeRaptorRequest() {
        profile = RaptorProfile.MULTI_CRITERIA_RANGE_RAPTOR;
        earliestDepartureTime = NOT_SET;
        latestArrivalTime = NOT_SET;
        searchWindowInSeconds = DEFAULT_SEARCH_WINDOW_1_HOUR_IN_SECONDS;
        arrivedBy = false;
        accessLegs = Collections.emptyList();
        egressLegs = Collections.emptyList();
        boardSlackInSeconds = 60;
        numberOfAdditionalTransfers = 3;
        multiCriteriaCostFactors = MultiCriteriaCostFactors.DEFAULTS;
        debug = DebugRequest.defaults();
    }

    RangeRaptorRequest(RequestBuilder<T> builder) {
        this.profile = builder.profile();
        this.earliestDepartureTime = builder.earliestDepartureTime();
        this.latestArrivalTime = builder.latestArrivalTime();
        this.searchWindowInSeconds = builder.searchWindowInSeconds();
        this.arrivedBy = builder.arrivedBy();
        this.accessLegs = new ArrayList<>(builder.accessLegs());
        this.egressLegs = new ArrayList<>(builder.egressLegs());
        this.boardSlackInSeconds = builder.boardSlackInSeconds();
        this.numberOfAdditionalTransfers = builder.numberOfAdditionalTransfers();
        this.multiCriteriaCostFactors = builder.buildMcCostFactors();
        this.debug = builder.debug();
    }

    public RequestBuilder<T> mutate() {
        return new RequestBuilder<T>(this);
    }

    /**
     * The profile/algorithm to use for this request.
     * <p/>
     * The default value is {@link RaptorProfile#MULTI_CRITERIA_RANGE_RAPTOR}
     */
    public RaptorProfile profile() {
        return profile;
    }

    /**
     * The beginning of the departure window, in seconds since midnight. Inclusive.
     * The earliest a journey may start in seconds since midnight. In the case of a 'depart after'
     * search this is a required. In the case of a 'arrive by' search this is optional.
     * <p/>
     * In Raptor terms this maps to the beginning of the departure window. The {@link #searchWindowInSeconds()}
     * is used to find the end of the time window.
     * <p/>
     * Required. Must be a positive integer, seconds since midnight(transit service time).
     * Required for 'depart after'. Must be a positive integer, seconds since midnight(transit service time).
     *
     */
    public int earliestDepartureTime() {
        return earliestDepartureTime;
    }

    /**
     * The end of the departure window, in seconds since midnight. Exclusive.
     * The latest a journey may arrive in seconds since midnight. In the case of a 'arrive by'
     * search this is a required. In the case of a 'depart after' search this is optional.
     * <p/>
     * Required. Must be a positive integer, seconds since midnight(transit service time).
     * In Raptor terms this maps to the beginning of the departure window of a reverse search. The
     * {@link #searchWindowInSeconds()} is used to find the end of the time window.
     * <p/>
     * Required for 'arrive by'. Must be a positive integer, seconds since midnight(transit service time).
     */
    public int latestArrivalTime() {
        return latestArrivalTime;
    }

    /**
     * The time window used to search. The unit is seconds. For a *depart by search*, this is
     * added to the 'earliestDepartureTime' to find the 'latestDepartureTime'. For a *arrive
     * by search* this is used to calculate the 'earliestArrivalTime'. The algorithm will find
     * all optimal travels within the given time window.
     * <p/>
     * Required. Must be a positive integer.
     */
    public int searchWindowInSeconds() {
        return searchWindowInSeconds;
    }


    /**
     * This parameter decide if a search is a 'depart after' or 'arrive by' search.
     * <p/>
     * Required. Initial value is 'false'.
     *
     * @return true is the search is a 'arrive by' search.
     */
    public boolean arrivedBy() {
        return arrivedBy;
    }

    /**
     * Times to access each transit stop using the street network in seconds.
     * <p/>
     * Required, at least one access leg must exist.
     */
    public Collection<TransferLeg> accessLegs() {
        return accessLegs;
    }

    /**
     * List of all possible egress stops and time to reach destination in seconds.
     * <p>
     * NOTE! The {@link TransferLeg#stop()} is the stop where the egress leg
     * start, NOT the destination - think of it as a reversed leg.
     * <p/>
     * Required, at least one egress leg must exist.
     */
    public Collection<TransferLeg> egressLegs() {
        return egressLegs;
    }

    /**
     * The minimum wait time for transit boarding to account for schedule variation.
     * This is added between transits, between transfer and transit, and between access "walk" and transit.
     * <p/>
     * The default value is 60.
     */
    public int boardSlackInSeconds() {
        return boardSlackInSeconds;
    }


    /**
     * RangeRaptor is designed to search until the destination is reached and then
     * {@code numberOfAdditionalTransfers} more rounds.
     * <p/>
     * The default value is 3.
     */
    public int numberOfAdditionalTransfers() {
        return numberOfAdditionalTransfers;
    }


    /**
     * The multi-criteria cost criteria factors.
     */
    public MultiCriteriaCostFactors multiCriteriaCostFactors() {
        return multiCriteriaCostFactors;
    }

    /**
     * Specify what to debug in the debug request.
     * <p/>
     * This feature is optional, by default debugging is turned off.
     */
    public DebugRequest<T> debug() {
        return debug;
    }

    @Override
    public String toString() {
        return "RangeRaptorRequest{" +
                "profile=" + profile +
                ", earliestDepartureTime=" + TimeUtils.timeToStrCompact(earliestDepartureTime, -1) +
                ", latestArrivalTime=" + TimeUtils.timeToStrCompact(latestArrivalTime, -1) +
                ", searchWindowInSeconds=" + TimeUtils.timeToStrCompact(searchWindowInSeconds) +
                ", arrivedBy=" + arrivedBy +
                ", accessLegs=" + accessLegs +
                ", egressLegs=" + egressLegs +
                ", boardSlackInSeconds=" + boardSlackInSeconds +
                ", numberOfAdditionalTransfers=" + numberOfAdditionalTransfers +
                ", multiCriteriaCostFactors=" + multiCriteriaCostFactors +
                ", debug=" + debug +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RangeRaptorRequest<?> that = (RangeRaptorRequest<?>) o;
        return earliestDepartureTime == that.earliestDepartureTime &&
                latestArrivalTime == that.latestArrivalTime &&
                searchWindowInSeconds == that.searchWindowInSeconds &&
                arrivedBy == that.arrivedBy &&
                boardSlackInSeconds == that.boardSlackInSeconds &&
                numberOfAdditionalTransfers == that.numberOfAdditionalTransfers &&
                profile == that.profile &&
                accessLegs.equals(that.accessLegs) &&
                egressLegs.equals(that.egressLegs) &&
                Objects.equals(multiCriteriaCostFactors, that.multiCriteriaCostFactors) &&
                Objects.equals(debug, that.debug);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                profile, earliestDepartureTime, latestArrivalTime, searchWindowInSeconds, arrivedBy, accessLegs,
                egressLegs, boardSlackInSeconds, numberOfAdditionalTransfers, multiCriteriaCostFactors, debug
        );
    }
}
