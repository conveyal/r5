package com.conveyal.r5.analyst.scenario;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import java.util.Set;
import java.util.stream.Collectors;

import com.conveyal.r5.transit.TripPattern;
import gnu.trove.set.TIntSet;

/**
 * Remove trips from a scenario.
 * Currently this can only remove all trips on an entire route.
 * In order to remove certain patterns we may want to support removing trips individually by tripId.
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
        boolean errorsResolving = false;
        boolean onlyOneDefined = (routes != null) ^ (trips != null); // Not bitwise, non-short-circuit logical XOR.
        if (!onlyOneDefined) {
            warnings.add("On RemoveTrips modifications, exactly one of routes or trips must be defined.");
            errorsResolving = true;
        }
        return errorsResolving;
    }

    @Override
    public boolean apply(TransportNetwork network) {
        TransitLayer transitLayer = network.transitLayer;
        if (routes != null) {
            transitLayer.tripPatterns = transitLayer.tripPatterns.stream()
                    .filter(pattern -> !routes.contains(pattern.routeId))
                    .collect(Collectors.toList());
        } else {
            transitLayer.tripPatterns = transitLayer.tripPatterns.stream()
                    .map(pattern -> filterPattern(pattern))
                    .collect(Collectors.toList());
        }
        return false;
    }

    private TripPattern filterPattern(TripPattern originalTripPattern) {
        if (originalTripPattern.tripSchedules.stream().noneMatch(schedule -> trips.contains(schedule.tripId))) {
            return originalTripPattern;
        }
        TripPattern newTripPattern = originalTripPattern.clone();
        newTripPattern.tripSchedules = originalTripPattern.tripSchedules.stream()
                .filter(schedule -> trips.contains(schedule.tripId))
                .collect(Collectors.toList());
        return newTripPattern;
    }

}
