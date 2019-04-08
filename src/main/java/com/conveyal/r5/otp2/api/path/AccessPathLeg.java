package com.conveyal.r5.otp2.api.path;

import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;

import java.util.Objects;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccessPathLeg<?> that = (AccessPathLeg<?>) o;
        return fromTime == that.fromTime &&
                toStop == that.toStop &&
                toTime == that.toTime &&
                next.equals(that.next);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromTime, toStop, toTime, next);
    }
}
