package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;

/**
 * A timetable filter that is applied to specific trips.
 */
public abstract class TripFilter extends TimetableFilter {

    /**
     * Apply this modification to a Trip. Do not modify the original trip times as they are part of the graph!
     * In R5 a TripSchedule can represent both schedule-based and frequency-based trips.
     */
    public abstract TripSchedule apply (TripPattern tp, TripSchedule tt);

}
