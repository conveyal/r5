package com.conveyal.r5.profile.entur.util;

/**
 * Create a path like: {@code walk 5:12 > 34567 > transit 12:10-12:50 > 23456 > walk 4:01 }
 */
public class PathStringBuilder {
    private StringBuilder buf = new StringBuilder();

    public PathStringBuilder sep() {
        return append(" > ");
    }

    public PathStringBuilder stop(int stop) {
        return append(stop);
    }

    public PathStringBuilder walk(int duration) {
        return append("Walk ").duration(duration);
    }

    public PathStringBuilder transit(int fromTime, int toTime) {
        return append("Transit ").time(fromTime, toTime);
    }

    @Override
    public String toString() {
        return buf.toString();
    }

    /* private helpers */

    private PathStringBuilder duration(int duration) {
        return append(TimeUtils.timeToStrCompact(duration));
    }

    private PathStringBuilder time(int from, int to) {
        return append(TimeUtils.timeToStrShort(from)).append("-").append(TimeUtils.timeToStrShort(to));
    }

    private PathStringBuilder append(String text) {
        buf.append(text);
        return this;
    }

    private PathStringBuilder append(int value) {
        buf.append(value);
        return this;
    }
}
