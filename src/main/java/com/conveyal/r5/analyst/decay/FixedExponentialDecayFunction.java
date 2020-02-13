package com.conveyal.r5.analyst.decay;

import com.google.common.base.Preconditions;
import org.apache.commons.math3.util.FastMath;

/**
 * This is equivalent to using the ExponentialDecayFunction with a cutoff (half-life) of log(0.5)/decayConstant.
 * Cutoffs can only be a whole number of minutes. This class allows directly setting a precise externally determined
 * constant, even if it does not correspond to an integer half-life. Note that this decay function will completely
 * ignore the travel time cutoff, and results will not change from one cutoff to another.
 */
public class FixedExponentialDecayFunction extends DecayFunction {

    /**
     * Note that this is a decay constant for travel times in seconds.
     * If your decay constant is for travel times in minutes, you will need to divide it by 60.
     * Values are expected to be negative to ensure decay (rather than growth).
     */
    public double decayConstant;

    @Override
    public double computeWeight (int cutoffSeconds, int travelTimeSeconds) {
        if (travelTimeSeconds <= 0) {
            return 1;
        }
        if (travelTimeSeconds >= TWO_HOURS_IN_SECONDS) {
            return 0;
        }
        return FastMath.exp(decayConstant * travelTimeSeconds);
    }

    @Override
    public void prepare () {
        Preconditions.checkArgument(decayConstant < 0);
        Preconditions.checkArgument(decayConstant > -1);
    }

}
