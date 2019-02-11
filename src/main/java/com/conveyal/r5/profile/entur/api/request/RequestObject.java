package com.conveyal.r5.profile.entur.api.request;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.Collection;

class RequestObject<T extends TripScheduleInfo> implements RangeRaptorRequest<T> {
        private final RaptorProfile profile;
        private final int fromTime;
        private final int toTime;
        private final Collection<TransferLeg> accessLegs;
        private final Collection<TransferLeg> egressLegs;
        private final int boardSlackInSeconds;
        private final int numberOfAdditionalTransfers;
        private final DebugRequest<T> debug;

    RequestObject(RequestBuilder<T> builder) {
            this.profile = builder.profile;
            this.fromTime = builder.fromTime;
            this.toTime = builder.toTime;
            this.accessLegs = builder.accessLegs;
            this.egressLegs = builder.egressLegs;
            this.boardSlackInSeconds = builder.boardSlackInSeconds;
            this.numberOfAdditionalTransfers = builder.numberOfAdditionalTransfers;
            this.debug = builder.debug();
        }

    @Override
    public RaptorProfile profile() {
        return profile;
    }

    @Override
    public int fromTime() {
        return fromTime;
    }

    @Override
    public int toTime() {
        return toTime;
    }

    @Override
    public Collection<TransferLeg> accessLegs() {
        return accessLegs;
    }

    @Override
    public Collection<TransferLeg> egressLegs() {
        return egressLegs;
    }

    @Override
    public int boardSlackInSeconds() {
        return boardSlackInSeconds;
    }

    @Override
    public int numberOfAdditionalTransfers() {
        return numberOfAdditionalTransfers;
    }

    @Override
    public DebugRequest<T> debug() {
        return debug;
    }

    @Override
    public String toString() {
        return "RequestObject{" +
                "from=" + TimeUtils.timeToStrLong(fromTime) +
                ", toTime=" + TimeUtils.timeToStrLong(toTime) +
                ", profile=" + profile +
                ", boardSlackInSeconds=" + boardSlackInSeconds +
                ", numberOfAdditionalTransfers=" + numberOfAdditionalTransfers +
                ", accessLegs=" + accessLegs +
                ", egressLegs=" + egressLegs +
                '}';
    }
}
