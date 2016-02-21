package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A Modification that is applied to each specific TripSchedule within a TripPattern separately.
 *
 * This has been deprecated, I'm leaving it around to copy methods from later.
 */
@Deprecated
public abstract class TripScheduleModification extends TripPatternModification implements Cloneable {

    /* Implementation of superclass method */

    @Override
    public TripPattern applyToTripPattern(TripPattern originalTripPattern) {
        TripPattern tripPattern = originalTripPattern.clone();
        tripPattern.tripSchedules = tripPattern.tripSchedules.stream()
                .map(ts -> this.applyToTripSchedule(tripPattern, ts))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        // If there were no changes, just use the original TripPattern
        if (tripPattern.tripSchedules.equals(originalTripPattern.tripSchedules)) {
            return originalTripPattern;
        }
        if (tripPattern.tripSchedules.isEmpty()) {
            return null; // Remove the TripPattern entirely
        } else {
            return tripPattern;
        }
    }

    /* Method to be implemented in subclasses */

    /**
     * A method that transforms an individual TripSchedule, performing protective copies as needed.
     * Returns the optionally transformed TripSchedule or null if the TripSchedule is to be removed entirely.
     * The TripPattern is passed in because TripSchedules to not hold a reference to their containing TripPatterns.
     * In R5 a TripSchedule can represent both schedule-based and frequency-based trips.
     */
    public abstract TripSchedule applyToTripSchedule (TripPattern tripPattern, TripSchedule tripSchedule);

}
