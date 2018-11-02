package com.conveyal.r5.profile.entur.api;


/**
 * This interface represent a trip pattern.
 */
public interface TripPatternInfo {
    // TODO TGR - add JavaDoc
    int originalPatternIndex();

    /**
     * stop index
     * @param stopPositionInPattern
     * @return
     */
    int currentPatternStop(int stopPositionInPattern);

    // TODO TGR - add JavaDoc
    int currentPatternStopsSize();

    // TODO TGR - add JavaDoc
    TripScheduleInfo getTripSchedule(int index);

    // TODO TGR - add JavaDoc
    int getTripScheduleSize();
}
