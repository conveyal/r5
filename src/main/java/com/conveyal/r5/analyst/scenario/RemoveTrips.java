package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Remove individual trips by ID, or remove all trips from an entire route.
 */
public class RemoveTrips extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(RemoveTrips.class);

    /** Which routes should have all their trips removed. */
    public Set<String> routes;

    /** One or more example tripIds on every pattern that is to be removed. */
    public Set<String> patterns;

    /** Which trips should be removed. */
    public Set<String> trips;

    /** The number of individual trips that were removed (not counting entire patterns). For logging and error detection. */
    private int nTripsRemoved = 0;

    @Override
    public boolean resolve (TransportNetwork network) {
        int nDefined = 0;
        checkIds(routes, patterns, trips, true, network);
        return errors.size() > 0;
    }

    @Override
    public boolean apply(TransportNetwork network) {
        TransitLayer transitLayer = network.transitLayer;
        int nPatternsBefore = transitLayer.tripPatterns.size();
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
        int nPatternsRemoved = nPatternsBefore - transitLayer.tripPatterns.size();
        LOG.info("Removed {} entire patterns. Removed {} individual trips specified by ID.", nPatternsRemoved, nTripsRemoved);
        if (nTripsRemoved == 0 && nPatternsRemoved == 0) {
            errors.add("No trips were removed.");
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
        nTripsRemoved += originalTripPattern.tripSchedules.size() - newTripPattern.tripSchedules.size();
        if (newTripPattern.tripSchedules.isEmpty()) {
            return null;
        } else {
            return newTripPattern;
        }
    }

    public int getSortOrder() { return 60; }

}
