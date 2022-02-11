package com.conveyal.r5.rastercost;

import org.apache.commons.math3.analysis.polynomials.PolynomialFunction;

import static com.google.common.base.Preconditions.checkState;

/**
 * Based on elevation changes along a road segment, computes energy consumption as a proxy for perceived effort.
 * https://journals.physiology.org/doi/full/10.1152/japplphysiol.01177.2001
 * However "They were all elite athletes practicing endurance mountain racing" so we might need to adjust this
 * or refer to other literature cited by this paper.
 */
public class MinettiCalculator implements ElevationCostField.ElevationCostCalculator {

    /**
     * Polynomial coefficients are given under figure 1 in the paper.
     * This is for walking (CWI) rather than running (CRI).
     */
    private static final PolynomialFunction minettiCwiPolynomial = new PolynomialFunction(new double[] {
            2.5, 19.6, 51.9, -76.8, -58.7, 280.5
    });

    /**
     * Value at zero is 1.5, minimum value is around 1.09 at -0.2, higer values are 10-15 for slopes around 0.3-0.4.
     * Normalized this gives a minimum of 0.73 and a typical maximum of 7-10.
     */
    private static final double normalizationFactor = 1 / minettiCwiPolynomial.value(0);

    private double weightedSum = 0;
    private double xDistance = 0;

    @Override
    public void consumeElevationSegment (int index, double xMeters, double yMeters) {
        weightedSum += xMeters * minettiCwi(xMeters, yMeters);
        xDistance += xMeters;
    }

    /** Return a travel time multiplier for the entire edge, a weighted sum of the factors for all segments. */
    public double weightedElevationFactor () {
        return weightedSum / xDistance;
    }


    public static double minettiCwi (double dx, double dy) {
        double gradient = dy/dx;
        if (!(Math.abs(gradient) < 0.5)) {
            System.out.printf("Gradient over 0.5: %f (dx %f / dy %f) Clamping.\n", gradient, dx, dy);
            if (gradient < -0.5) {
                gradient = -0.5;
            } else if (gradient > 0.5) {
                gradient = 0.5;
            }
        }
        final double cwi = minettiCwiPolynomial.value(gradient);
        if (!(cwi > 0.9 && cwi < 25)) {
            System.out.println("Minetti effort result outside expected range: " + cwi);
        }
        return cwi * normalizationFactor; // obviously it would be more efficient to scale the polynomial itself
    }

}
