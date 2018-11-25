package com.conveyal.r5.profile.entur.api.path;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

/**
 * Abstract intermediate leg in a path. It is either a Transit or Transfer leg.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public abstract class IntermediatePathLeg<T extends TripScheduleInfo> implements PathLeg<T> {
    private final int fromStop;
    private final int fromTime;
    private final int toStop;
    private final int toTime;

    IntermediatePathLeg(int fromStop, int fromTime, int toStop, int toTime) {
        this.fromStop = fromStop;
        this.fromTime = fromTime;
        this.toStop = toStop;
        this.toTime = toTime;
    }

    /**
     * The stop index where the leg start. Also called departure stop index.
     */
    public final int fromStop() {
        return fromStop;
    }

    @Override
    public final int fromTime() {
        return fromTime;
    }

    /**
     * The stop index where the leg end, also called arrival stop index.
     */
    public final int toStop(){
        return toStop;
    }

    @Override
    public final int toTime(){
        return toTime;
    }
}
