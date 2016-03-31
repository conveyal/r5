package com.conveyal.r5.analyst.scenario;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.conveyal.r5.transit.TripPattern;
import gnu.trove.set.TIntSet;

/**
 * Remove individual trips by ID, or remove all trips from an entire route.
 */
public class RemoveTrips extends Modification {

    public static final long serialVersionUID = 1L;

    /** Which routes should have all their trips removed. */
    public Set<String> routes;

    /** Which trips should be removed. */
    public Set<String> trips;

    // FIXME What is this? Are these pattern ID numbers supposed to be supplied in the API call or pre-looked-up?
    // This is set in SuboptimalPathProfileRouter, and should probably be moved into another Modification.
    public TIntSet patternIds;

    @Override
    public String getType() {
        return "remove-trips";
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        boolean onlyOneDefined = (routes != null) ^ (trips != null); // Not bitwise, non-short-circuit logical XOR.
        if (!onlyOneDefined) {
            warnings.add("On RemoveTrips modifications, exactly one of routes or trips must be defined.");
        }
        return warnings.size() > 0;
    }

    @Override
    public boolean apply(TransportNetwork network) {
        TransitLayer transitLayer = network.transitLayer;
        if (routes != null) {
            // Remove entire routes, not specific trips.
            transitLayer.tripPatterns = transitLayer.tripPatterns.stream()
                    .filter(pattern -> !routes.contains(pattern.routeId))
                    .collect(Collectors.toList());
        } else {
            // Remove specific trips, not entire routes.
            transitLayer.tripPatterns = transitLayer.tripPatterns.stream()
                    .map(pattern -> processPattern(pattern))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return false;
    }

    private TripPattern processPattern (TripPattern originalTripPattern) {
        // This causes an additional loop over every pattern's contents, but avoids a clone.
        if (originalTripPattern.tripSchedules.stream().noneMatch(schedule -> trips.contains(schedule.tripId))) {
            return originalTripPattern;
        }
        TripPattern newTripPattern = originalTripPattern.clone();
        newTripPattern.tripSchedules = originalTripPattern.tripSchedules.stream()
                .filter(schedule -> trips.contains(schedule.tripId))
                .collect(Collectors.toList());
        if (newTripPattern.tripSchedules.isEmpty()) {
            return null;
        } else {
            return newTripPattern;
        }
    }

}
