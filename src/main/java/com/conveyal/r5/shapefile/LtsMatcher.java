package com.conveyal.r5.shapefile;

import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import org.opengis.feature.simple.SimpleFeature;

import static com.conveyal.r5.labeling.LevelOfTrafficStressLabeler.intToLts;
import static com.conveyal.r5.streets.EdgeStore.EdgeFlag.BIKE_LTS_EXPLICIT;

public class LtsMatcher extends ShapefileMatcher {

    public LtsMatcher (StreetLayer streets) {
        super(streets);
    }

    /**
     * Copy LTS attribute from the supplied feature to the pair of edges, setting the BIKE_LTS_EXPLICIT flag. This
     * will prevent Conveyal OSM-inferred LTS from overwriting the shapefile-derived LTS.
     *
     * In current usage this is applied after the OSM is already completely loaded and converted to network edges, so
     * it overwrites any data from OSM. Perhaps instead of BIKE_LTS_EXPLICIT we should have an LTS source flag:
     * OSM_INFERRED, OSM_EXPLICIT, SHAPEFILE_MATCH etc. This could also apply to things like speeds and slopes. The
     * values could be retained only for the duration of network building unless we have a reason to keep them.
     */
    @Override
    void setEdgePair (SimpleFeature feature, int attributeIndex, EdgeStore.Edge edge) {
        // Set flags on forward and backward edges to match those on feature attribute
        // TODO reuse code from LevelOfTrafficStressLabeler.label()
        int lts = ((Number) feature.getAttribute(attributeIndex)).intValue();
        if (lts < 1 || lts > 4) {
            LOG.error("Clamping LTS value to range [1...4]. Value in attribute is {}", lts);
        }
        EdgeStore.EdgeFlag ltsFlag = intToLts(lts);
        edge.setFlag(BIKE_LTS_EXPLICIT);
        edge.setFlag(ltsFlag);
        edge.advance();
        edge.setFlag(BIKE_LTS_EXPLICIT);
        edge.setFlag(ltsFlag);
    }
}
