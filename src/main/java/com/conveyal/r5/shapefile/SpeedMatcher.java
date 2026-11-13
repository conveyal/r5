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
     * Set the speed value of an edge using a supplied feature with a speed attribute (converts from miles per hour).
     * If a supplied feature has speed 0 or less, it is ignored.
     */
    @Override
    void setEdgePair (SimpleFeature feature, int attributeIndex, EdgeStore.Edge edge) {
        if (feature.getAttribute(attributeIndex) != null) {
            double speedMph = ((Number) feature.getAttribute(attributeIndex)).doubleValue();
            if (speedMph > 0) {
                double speedKph = KPH_PER_MPH * speedMph;
                edge.setSpeedKph(speedKph);
                edge.advance();
                edge.setSpeedKph(speedKph);
            }
        }
    }
}
