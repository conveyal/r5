package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.Collection;

/**
 * A wrapper around the original request object witch make the necessary adjustments
 * to convert a normel request object into a request prepared for a reverse search.
 * <p/>
 * This class swap:
 * <ul>
 *     <li> earliestDepartureTime  and latestArrivalTime
 *     <li> accessLegs and egressLegs
 * </ul>
 * and wrap debug.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class ReverseRequest<T extends TripScheduleInfo> extends RangeRaptorRequest<T> {
    private final DebugRequest<T> debug;

    ReverseRequest(RangeRaptorRequest<T> original) {
        super(original);
        debug = original.debug() == null ? null : new ReverseDebugRequest<>(original.debug());
    }

    @Override
    public int earliestDepartureTime() {
        return super.latestArrivalTime();
    }

    @Override
    public int latestArrivalTime() {
        return super.earliestDepartureTime();
    }

    @Override
    public Collection<TransferLeg> accessLegs() {
        return super.egressLegs();
    }

    @Override
    public Collection<TransferLeg> egressLegs() {
        return super.accessLegs();
    }

    @Override
    public DebugRequest<T> debug() {
        return debug;
    }

    @Override
    public String toString() {
        return "RangeRaptorRequest{" +
                "profile=" + profile() +
                ", earliestDepartureTime=" + earliestDepartureTime() +
                ", latestArrivalTime=" + latestArrivalTime() +
                ", searchWindowInSeconds=" + searchWindowInSeconds() +
                ", arrivedBy=" + arrivedBy() +
                ", accessLegs=" + accessLegs() +
                ", egressLegs=" + egressLegs() +
                ", boardSlackInSeconds=" + boardSlackInSeconds() +
                ", numberOfAdditionalTransfers=" + numberOfAdditionalTransfers() +
                ", multiCriteriaCostFactors=" + multiCriteriaCostFactors() +
                ", debug=" + debug() +
                '}';
    }
}
