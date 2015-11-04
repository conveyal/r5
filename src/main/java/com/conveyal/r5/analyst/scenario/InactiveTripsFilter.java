package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransitLayer;

/**
 * A Filter that removes any trips that are not running in a given time window.
 * This reduces the size of the timetables before the search.
 */
public class InactiveTripsFilter extends TransitLayerModification {

    @Override
    public String getType() {
        return null; // Should never be serialized
    }

    @Override
    protected TransitLayer applyToTransitLayer(TransitLayer originalTransitLayer) {
        // TODO implement
        return originalTransitLayer;
    }

}
