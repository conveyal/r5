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

    /** One or more example tripIds on every pattern that is to be removed. */
    public Set<String> patterns;

    /** Which trips should be removed. */
    public Set<String> trips;

    @Override
    public String getType() {
        return "remove-trips";
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        int nDefined = 0;
        if (routes != null) nDefined += 1;
        if (trips!= null) nDefined += 1;
        if (patterns != null) nDefined += 1;
        if (nDefined != 1) {
            warnings.add("Exactly one of routes, patterns, or trips must be provided.");
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
        } else if (patterns != null) {
            // Remove entire patterns, not specific trips.
            transitLayer.tripPatterns = transitLayer.tripPatterns.stream()
                    .filter(pattern -> pattern.containsNoTrips(patterns))
                    .collect(Collectors.toList());
        } else if (trips != null) {
            // Remove specific trips, not entire routes.
            transitLayer.tripPatterns = transitLayer.tripPatterns.stream()
                    .map(pattern -> processPattern(pattern))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return false;
    }

    private TripPattern processPattern (TripPattern originalTripPattern) {
        if (originalTripPattern.containsNoTrips(trips)) {
            // Avoid unnecessary new lists and cloning when no trips in this pattern are affected.
            return originalTripPattern;
        }
        TripPattern newTripPattern = originalTripPattern.clone();
        newTripPattern.tripSchedules = originalTripPattern.tripSchedules.stream()
                .filter(schedule -> !trips.contains(schedule.tripId))
                .collect(Collectors.toList());
        if (newTripPattern.tripSchedules.isEmpty()) {
            return null;
        } else {
            return newTripPattern;
        }
    }

    public int getSortOrder() { return 60; }

}
