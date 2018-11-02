package com.conveyal.r5.profile.entur.api;

// TODO TGR - add JavaDoc
public interface Pattern {
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
