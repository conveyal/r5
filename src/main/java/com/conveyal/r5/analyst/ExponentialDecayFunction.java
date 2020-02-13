package com.conveyal.r5.analyst;

import org.apache.commons.math3.util.FastMath;

/**
 * The cutoff parameter c is treated as the half-life. So rather than working with the base e, we get:
 * N(t) = 2^(-t/c)
 *
 * Exp() is generally faster than the more general purpose pow().
 * since pow(x,y) = exp(y*log(x))
 * N(t) = exp( log(2) * (-t / c) )
 * N(t) = exp( (-log(2) / c) * t )
 *
 * JIT may be smart enough to factor the -(log(2)/cutoff) out of the series of method calls, without creating specific
 * function instances with precomputed constants for each separate cutoff.
 */
public class ExponentialDecayFunction extends DecayFunction {

    private static final double negativeLog2 = -FastMath.log(2);

    @Override
    public double computeWeight (int cutoffSeconds, int travelTimeSeconds) {
        if (travelTimeSeconds <= 0) {
            return 1;
        }
        if (travelTimeSeconds >= TWO_HOURS_IN_SECONDS) {
            return 0;
        }
        return FastMath.exp(negativeLog2 / cutoffSeconds * travelTimeSeconds);
    }

    @Override
    public void prepare () { }

}
