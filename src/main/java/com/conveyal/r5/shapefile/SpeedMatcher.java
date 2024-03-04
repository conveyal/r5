package com.conveyal.r5.shapefile;

import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import org.opengis.feature.simple.SimpleFeature;

public class SpeedMatcher extends ShapefileMatcher {

    static double KPH_PER_MPH = 1.609344;
    public SpeedMatcher (StreetLayer streets) {
        super(streets);
    }

    /**
     * Set the speed value of an edge using a supplied feature with a speed attribute (in kilometers per hour)
     */
    @Override
    void setEdgePair (SimpleFeature feature, int attributeIndex, EdgeStore.Edge edge) {
        if (feature.getAttribute(attributeIndex) != null) {
            double speedKph = KPH_PER_MPH * ((Number) feature.getAttribute(attributeIndex)).doubleValue();
            edge.setSpeedKph(speedKph);
            edge.advance();
            edge.setSpeedKph(speedKph);
        }
    }
}
