package com.conveyal.r5.streets;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.rastercost.CostField;
import com.conveyal.r5.rastercost.SunLoader;
import org.apache.commons.math3.util.FastMath;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This layers zero or more transformations on top of the street traversal times, to account for hills, sun, noise, etc.
 * The base calculator also produces turn costs, which are not transformed.
 */
public class MultistageTraversalTimeCalculator implements TraversalTimeCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private TraversalTimeCalculator base;

    private List<CostField> costFields;

    public MultistageTraversalTimeCalculator (TraversalTimeCalculator base, List<CostField> costFields) {
        checkNotNull(base);
        checkNotNull(costFields);
        this.base = base;
        this.costFields = costFields;
    }

    @Override
    public int traversalTimeSeconds (EdgeStore.Edge currentEdge, StreetMode streetMode, ProfileRequest req) {
        final int baseTraversalTimeSeconds = base.traversalTimeSeconds(currentEdge, streetMode, req);
        int t = baseTraversalTimeSeconds;
        for (CostField costField : costFields) {
            t += costField.additionalTraversalTimeSeconds(currentEdge, baseTraversalTimeSeconds);
        }
        if (t < 1) {
            LOG.warn("Cost was negative or zero. Clamping to 1 second.");
            t = 1;
        }
        return t;
    }

    @Override
    public int turnTimeSeconds (int fromEdge, int toEdge, StreetMode streetMode) {
        return base.turnTimeSeconds(fromEdge, toEdge, streetMode);
    }

}
