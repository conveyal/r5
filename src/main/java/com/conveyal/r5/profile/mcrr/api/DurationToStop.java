package com.conveyal.r5.profile.mcrr.api;


import com.conveyal.r5.profile.mcrr.util.TimeUtils;

import java.util.Objects;

/**
 * Tuple for destination stop and a duration to reach the stop. The denature place is
 * given by the context and not part of the class.
 */
public final class DurationToStop {

    /** The destination stop */
    public final int stop;

    /** The duration to reach the stop in seconds. */
    public final int time;

    public DurationToStop(int stop, int time) {
        this.stop = stop;
        this.time = time;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DurationToStop that = (DurationToStop) o;

        return stop == that.stop && time == that.time;
    }

    @Override
    public int hashCode() {
        return Objects.hash(stop, time);
    }

    @Override
    public String toString() {
        return TimeUtils.timeToStrCompact(time) + " to " + stop;
    }
}
