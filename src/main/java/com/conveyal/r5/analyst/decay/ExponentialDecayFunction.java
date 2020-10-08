package com.conveyal.r5.analyst.decay;

import org.apache.commons.math3.util.FastMath;

/**
 * This an exponential decay function where the cutoff parameter c is treated as the half-life.
 * So rather than working with the base e, we use: N(t) = 2^(-t/c) = 0.5^(t/c)
 * Exp() is usually faster than the more general pow().
 * Since pow(x,y) = exp(y*log(x)) and we generally hold c constant while varying t:
 * N(t) = exp(log(0.5) * t/c)
 * JIT may be smart enough to factor the (log(0.5)/cutoff) out of the series of method calls, without us needing to
 * create specific function instances with precomputed constants for each separate cutoff.
 */
public class ExponentialDecayFunction extends DecayFunction {

    private static final double logOneHalf = FastMath.log(0.5);

    @Override
    public double computeWeight (int cutoffSeconds, int travelTimeSeconds) {
        if (travelTimeSeconds <= 0) {
            return 1;
        }
        if (travelTimeSeconds >= TWO_HOURS_IN_SECONDS) {
            return 0;
        }
        return FastMath.exp(logOneHalf / cutoffSeconds * travelTimeSeconds);
    }

    @Override
    public void prepare () { }

}
