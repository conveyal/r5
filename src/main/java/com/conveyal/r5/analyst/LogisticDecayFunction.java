package com.conveyal.r5.analyst;

import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.commons.math3.util.FastMath.PI;
import static org.apache.commons.math3.util.FastMath.exp;
import static org.apache.commons.math3.util.FastMath.sqrt;

/**
 * The logistic cumulative distribution function, expressed such that parameters are the median (inflection
 * point) and standard deviation. From Bauer and Groneberg equation 9. This applies a sigmoid rolloff.
 * The parameters can be set to reflect the mean and standard deviation of travel times in the commuting population.
 */
public class LogisticDecayFunction extends DecayFunction {

    private static final double SQRT3 = sqrt(3);

    public int standardDeviationSeconds;

    /**
     * Map from cutoffs (in seconds) to weight factors for all travel times.
     * However: number crunching is typically much faster than memory access on modern machines, so this may not be
     * needed.
     */
    TIntObjectMap<double[]> lookupTable = new TIntObjectHashMap();

    @Override
    public int reachesZeroAt (int cutoffSeconds) {
        return TWO_HOURS_IN_SECONDS;
    }

    @Override
    protected void validateParameters () {
        checkArgument(standardDeviationSeconds > 0);
        checkArgument(standardDeviationSeconds < TWO_HOURS_IN_SECONDS);
    }

    @Override
    public double computeWeight (int cutoffSeconds, int travelTimeSeconds) {
        return g(cutoffSeconds, 0) / g(cutoffSeconds, travelTimeSeconds);
    }

    private double g (int median, int travelTime) {
        return 1 + exp(((travelTime - median) * PI) / (standardDeviationSeconds * SQRT3));
    }

}
