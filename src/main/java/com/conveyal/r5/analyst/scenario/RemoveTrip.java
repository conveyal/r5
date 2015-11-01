package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;

/**
 * Remove trips from a scenario.
 * This could remove all trips on a route, since its superclass TimetableFilter provides both route and trip matching.
 */
public class RemoveTrip extends TripFilter {
    public static final long serialVersionUID = 1L;

    @Override
    public String getType() {
        return "remove-trip";
    }

    @Override
    public TripSchedule apply(TripPattern tp, TripSchedule tt) {
        return matches(tt.tripId) ? null : tt;
    }

}
