package com.conveyal.r5.profile.entur.api.path;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

/**
 * Represent a transit leg in a path.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class TransitPathLeg<T extends TripScheduleInfo> extends IntermediatePathLeg<T> {

    private final PathLeg<T> next;
    private final T trip;

    public TransitPathLeg(int fromStop, int fromTime, int toStop, int toTime, T trip, PathLeg<T> next) {
        super(fromStop, fromTime, toStop, toTime);
        this.next = next;
        this.trip = trip;
    }

    /**
     * The trip schedule info object passed into Raptor routing algorithm. 
     */
    public T trip() {
        return trip;
    }

    @Override
    public final boolean isTransitLeg() {
        return true;
    }

    @Override
    public final PathLeg<T> nextLeg() {
        return next;
    }
}
