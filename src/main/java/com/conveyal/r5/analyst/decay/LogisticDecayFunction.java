package com.conveyal.r5.analyst.decay;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.commons.math3.util.FastMath.PI;
import static org.apache.commons.math3.util.FastMath.exp;
import static org.apache.commons.math3.util.FastMath.sqrt;

/**
 * The logistic cumulative distribution function, expressed such that parameters are the median (inflection
 * point) and standard deviation. From Bauer and Groneberg equation 9. This applies a sigmoid rolloff.
 * The parameters can be set to reflect the mean and standard deviation of travel times in the commuting population.
 * Number crunching is typically much faster than memory access on modern machines, we should only implement a
 * table-lookup optimization if we can show significant slowdown due to these on the fly calculations.
 */
public class LogisticDecayFunction extends DecayFunction {

    private static final double SQRT3 = sqrt(3);

    public double standardDeviationMinutes;

    private double standardDeviationSeconds;

    @Override
    public void prepare () {
        // Derive and validate paramters.
        standardDeviationSeconds = standardDeviationMinutes * 60;
        checkArgument(standardDeviationSeconds > 0, "Standard deviation must be positive.");
        checkArgument(standardDeviationSeconds < TWO_HOURS_IN_SECONDS, "Standard deviation must be less than 2 hours.");
    }

    @Override
    public double computeWeight (int cutoffSeconds, int travelTimeSeconds) {
        return g(cutoffSeconds, 0) / g(cutoffSeconds, travelTimeSeconds);
    }

    private double g (int median, int travelTime) {
        return 1 + exp(((travelTime - median) * PI) / (standardDeviationSeconds * SQRT3));
    }

}
