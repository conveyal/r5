package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Remove trips from a scenario.
 * Currently this can only remove all trips on an entire route.
 * In order to remove certain patterns we may want to support removing trips individually by tripId.
 */
public class RemoveTrip extends Modification {

    public static final long serialVersionUID = 1L;

    /** On which route the stops should be skipped. */
    public Set<String> routeId;

    @Override
    public String getType() {
        return "remove-trip";
    }

    @Override
    public boolean apply(TransportNetwork network) {
        TransitLayer transitLayer = network.transitLayer.clone();
        transitLayer.tripPatterns = transitLayer.tripPatterns.stream()
            .filter(tp -> !routeId.contains(tp.routeId)).collect(Collectors.toList());
        network.transitLayer = transitLayer;
        return false;
    }

}
