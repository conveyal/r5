package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransitLayer;

/**
 * A type of Modification that only modifies the TransitLayer, not the StreetLayer.
 */
public abstract class TransitLayerModification extends Modification {
    
    /**
     * @return the original street layer to avoid unnecessary copying.
     */
    public StreetLayer applyToStreetLayer (StreetLayer originalStreetLayer) {
        return originalStreetLayer;
    }

}
