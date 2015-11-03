package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;

/**
 * Remove trips from a scenario.
 * This could remove all trips on a route, since its superclass TimetableFilter provides both route and trip matching.
 */
public class RemoveTrip extends TripScheduleModification {

    public static final long serialVersionUID = 1L;

    @Override
    public String getType() {
        return "remove-trip";
    }

    @Override
    public TripSchedule applyToTripSchedule(TripPattern tripPattern, TripSchedule tripSchedule) {
        return matches(tripSchedule.tripId) ? null : tripSchedule;
    }

}
