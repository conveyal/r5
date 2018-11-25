package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.Objects;


/**
 * Value-object representing a time interval: [from-time, to-time] as seconds.
 * <p/>
 * This class is immutable.
 */
public final class TimeInterval {
    /**
     * Start (or from) time in seconds.
     */
    public final int from;

    /**
     * End (or to) time in seconds.
     */
    public final int to;


    public TimeInterval(int from, int to) {
        this.from = from;
        this.to = to;
    }

    /**
     * Return the values using {@link TimeUtils#timeToStrCompact(int)} to format the time like
     * this:
     * <ul>
     * <li>{@code "9:30.45 - 17:00"}
     * <li>{@code "34245 - 61200"}   {@code TimeUtils.RAW_TIME_MODE}.
     * </ul>
     */
    @Override
    public String toString() {
        return TimeUtils.timeToStrCompact(from) + " - " + TimeUtils.timeToStrCompact(to);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TimeInterval that = (TimeInterval) o;

        return from == that.from && to == that.to;
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to);
    }
}
