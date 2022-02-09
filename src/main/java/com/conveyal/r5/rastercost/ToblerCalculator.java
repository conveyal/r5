package com.conveyal.r5.rastercost;

import org.apache.commons.math3.analysis.polynomials.PolynomialFunction;
import org.apache.commons.math3.util.FastMath;

/**
 * Created by abyrd on 2021-07-20
 */
public class ToblerCalculator implements ElevationCostField.ElevationSegmentConsumer {

    private double weightedToblerSum = 0;
    private double xDistanceConsumed = 0;

    @Override
    public void consumeElevationSegment (int index, double xMeters, double yMeters) {
        //System.out.printf("Tobler: %f %f %f\n", xMeters, yMeters, tobler(xMeters, yMeters));
        weightedToblerSum += xMeters * minettiCwi(xMeters, yMeters); // tobler(xMeters, yMeters);
        xDistanceConsumed += xMeters;
    }

    public double getDistanceConsumed () {
        return xDistanceConsumed;
    }

    /**
     * Return a travel time multiplier for the entire edge, a weighted sum of the factors for all segments.
     * FIXME this will need to be inverted when we switch back to Tobler, which gives speed units not an effort multiplier.
     */
    public double weightedToblerFactor () {
        return weightedToblerSum / xDistanceConsumed;
    }

    /**
     * Tobler's hiking function, normalized to 1 rather than 5 on flat ground so results can scale user-specified speeds.
     * The function peaks at 6 on a slight downgrade, so we apply the factor 6/5 or 1.2.
     * Elevation points are evenly spaced. We can store average normalized speeds over the linestring for an edge.
     * See: https://en.wikipedia.org/wiki/Tobler%27s_hiking_function
     * Also: https://wildfiretoday.com/documents/Slope_travel_rates.pdf
     */
    public static double tobler (double dx, double dy) {
        return 1.2 * FastMath.exp(-3.5 * FastMath.abs((dy/dx) + 0.05));
    }

    public static final PolynomialFunction minettiCwiPolynomial = new PolynomialFunction(new double[] {
        2.5, 19.6, 51.9, -76.8, -58.7, 280.5
    });

    public static final double minettiNormalizationFactor = 1 / minettiCwiPolynomial.value(0);

    /**
     * Energy consumption as a proxy for perceived effort.
     * https://journals.physiology.org/doi/full/10.1152/japplphysiol.01177.2001
     */
    public static double minettiCwi (double dx, double dy) {
        final double gradient = dy/dx;
        final double cwi = minettiCwiPolynomial.value(gradient);
        return cwi * minettiNormalizationFactor; // obviously it would be more efficient to scale the polynomial itself
    }

}
