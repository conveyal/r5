package com.conveyal.r5.api.util;

import java.time.LocalDateTime;
import java.util.List;

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

    //Do we have realTime data for arrival/deparure times
    public boolean realTime;

    //arrival times of from stop in this pattern
    public List<LocalDateTime> fromArrivalTime;
    //departure times of from stop in this pattern
    public List<LocalDateTime> fromDepartureTime;

    //arrival times of to stop in this pattern
    public List<LocalDateTime> toArrivalTime;
    //departure times of to stop in this pattern
    public List<LocalDateTime> toDepartureTime;

    //TODO: from/to Departure/Arrival delay
    //TODO: should this be just the time or ISO date or local date
    //Probably ISO datetime

    @Override
    public int compareTo (SegmentPattern other) {
        return other.nTrips - this.nTrips;
    }
}
