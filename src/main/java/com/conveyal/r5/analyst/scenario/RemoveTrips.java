package com.conveyal.r5.analyst.scenario;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import java.util.Set;
import java.util.stream.Collectors;
import gnu.trove.set.TIntSet;

/**
 * Remove trips from a scenario.
 * Currently this can only remove all trips on an entire route.
 * In order to remove certain patterns we may want to support removing trips individually by tripId.
 */
public class RemoveTrips extends Modification {

    public static final long serialVersionUID = 1L;

    /** On which route the stops should be skipped. */
    public Set<String> routeId;

    // FIXME What is this? Are these pattern ID numbers supposed to be supplied in the API call or pre-looked-up?
    // This is set in SuboptimalPathProfileRouter
    public TIntSet patternIds;

    @Override
    public String getType() {
        return "remove-trips";
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
