package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.request.RaptorProfile;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.Collection;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class ReverseRequestObject<T extends TripScheduleInfo> implements RangeRaptorRequest<T> {

    private final RangeRaptorRequest<T> original;
    private final DebugRequest<T> debug;

    ReverseRequestObject(RangeRaptorRequest<T> original) {
        this.original = original;
        debug = original.debug() == null ? null : new ReverseDebugRequest<>(original.debug());
    }

    @Override
    public RaptorProfile profile() {
        return original.profile();
    }

    @Override
    public int fromTime() {
        return original.toTime();
    }

    @Override
    public int toTime() {
        return original.fromTime();
    }

    @Override
    public Collection<TransferLeg> accessLegs() {
        return original.egressLegs();
    }

    @Override
    public Collection<TransferLeg> egressLegs() {
        return original.accessLegs();
    }

    @Override
    public int boardSlackInSeconds() {
        return original.boardSlackInSeconds();
    }

    @Override
    public int numberOfAdditionalTransfers() {
        return original.numberOfAdditionalTransfers();
    }

    @Override
    public DebugRequest<T> debug() {
        return debug;
    }

}
