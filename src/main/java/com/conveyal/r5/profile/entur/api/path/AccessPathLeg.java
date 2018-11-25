package com.conveyal.r5.profile.entur.api.path;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

/**
 * Represent an access leg in a path. The access leg is the first leg from origin to the
 * first transit leg. The next leg must be a transit leg - no other legs are allowed.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class AccessPathLeg<T extends TripScheduleInfo> implements PathLeg<T> {
    private final int fromTime;
    private final int toStop;
    private final int toTime;
    private final TransitPathLeg<T> next;


    public AccessPathLeg(int fromTime, int toStop, int toTime, TransitPathLeg<T> next) {
        this.fromTime = fromTime;
        this.toStop = toStop;
        this.toTime = toTime;
        this.next = next;
    }

    @Override
    public int fromTime() {
        return fromTime;
    }

    /**
     * The stop index where the leg end, also called arrival stop index.
     */
    public int toStop() {
        return toStop;
    }

    @Override
    public int toTime() {
        return toTime;
    }

    @Override
    public TransitPathLeg<T> nextLeg() {
        return next;
    }
}
