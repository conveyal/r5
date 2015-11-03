package com.conveyal.r5.api.util;

/**
 * Created by mabu on 2.11.2015.
 */
public  class SegmentPattern implements Comparable<SegmentPattern> {
    /**
     * Trip Pattern id TODO: trippattern?
     * @notnull
     */
    public String patternId;

    /**
     * Index of stop in given TripPatern where trip was started
     * @notnull
     */
    public int fromIndex;

    /**
     * Index of stop in given TripPattern where trip was stopped
     * @notnull
     */
    public int toIndex;

    /**
     * Number of trips (on this pattern??)
     * @notnull
     */
    public int nTrips;

    @Override
    public int compareTo (SegmentPattern other) {
        return other.nTrips - this.nTrips;
    }
}
