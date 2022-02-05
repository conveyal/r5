package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.EdgeStore;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.util.factory.Hints;

import java.io.File;

/**
 * Represents the additional cost of traversing edges that are not shaded from the sun.
 * Requires a detailed input raster of which...
 */
public class SunCostField implements CostField {
    public SunCostField () {
    }

    @Override
    public int transformTraversalTimeSeconds (EdgeStore.Edge currentEdge, int traversalTimeSeconds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getDisplayKey () {
        return "sun";
    }

    @Override
    public double getDisplayValue (int edgeIndex) {
        throw new UnsupportedOperationException();
    }
}
