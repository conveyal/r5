package com.conveyal.r5.rastercost;

import org.apache.commons.math3.analysis.polynomials.PolynomialFunction;
import org.apache.commons.math3.util.FastMath;

/**
 * Tobler's hiking function, normalized to 1 rather than 5 on flat ground so results can scale user-specified speeds.
 * The function peaks at 6 on a slight downgrade, so we apply the factor 6/5 or 1.2.
 * Elevation points are evenly spaced. We can store average normalized speeds over the linestring for an edge.
 * See: https://en.wikipedia.org/wiki/Tobler%27s_hiking_function
 * Also: https://wildfiretoday.com/documents/Slope_travel_rates.pdf
 *
 * It would be possible to factor out the weighted sum implementation and specify only the per-segment function.
 * But this assumes all functions will always use the same method for combining short segments into a single output.
 */
public class ToblerCalculator implements ElevationCostField.ElevationCostCalculator {

    private double weightedToblerSum = 0;
    private double xDistance = 0;

    private static double tobler (double dx, double dy) {
        return 1.2 * FastMath.exp(-3.5 * FastMath.abs((dy/dx) + 0.05));
    }

    @Override
    public void consumeElevationSegment (int index, double xMeters, double yMeters) {
        weightedToblerSum += xMeters * tobler(xMeters, yMeters);
        xDistance += xMeters;
    }

    /**
     * Return a travel time multiplier for the entire edge, a weighted sum of the factors for all segments.
     * This is inverted, as Tobler produces speed units and we want a time or effort multiplier.
     */
    public double weightedElevationFactor () {
        return 1 / (weightedToblerSum / xDistance);
    }

}
