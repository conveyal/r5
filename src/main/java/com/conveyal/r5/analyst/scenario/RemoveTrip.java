package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;

import java.util.Objects;

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
    public TripPattern applyToTripPattern(TripPattern originalTripPattern) {
        // Check first whether it's even possible that this Modification will apply to any trips on this pattern
        if (!couldMatch(originalTripPattern)) {
            return originalTripPattern;
        }
        // Stopgap efficient check for route filtration.
        // All trips on a TripPattern are on the same route so we can remove them all at once.
        if (routeId != null && (tripId == null || tripId.isEmpty())) {
            if (routeId.contains(originalTripPattern.routeId)) {
                return null;
            }
        }
        // Actually apply the Modification to each TripSchedule within this TripPattern individually.
        return super.applyToTripPattern(originalTripPattern);
    }

    @Override
    public TripSchedule applyToTripSchedule(TripPattern tripPattern, TripSchedule tripSchedule) {
        return matches(tripSchedule.tripId) ? null : tripSchedule;
    }

}
