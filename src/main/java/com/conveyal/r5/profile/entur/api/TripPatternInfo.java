package com.conveyal.r5.profile.entur.api;


/**
 * This interface represent a trip pattern.
 */
public interface TripPatternInfo<T extends TripScheduleInfo> {

    /**
     * stop index in pattern
     */
    int currentPatternStop(int stopPositionInPattern);

    // TODO TGR - add JavaDoc
    int numberOfStopsInPattern();

    // TODO TGR - add JavaDoc
    T getTripSchedule(int index);

    // TODO TGR - add JavaDoc
    int numberOfTripSchedules();
}
