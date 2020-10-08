package com.conveyal.r5.analyst.decay;

import com.google.common.base.Preconditions;
import org.apache.commons.math3.util.FastMath;

/**
 * This is equivalent to using the ExponentialDecayFunction with a cutoff (half-life) of log(0.5)/decayConstant.
 * Cutoffs can only be a whole number of minutes. This class allows directly setting a precise externally determined
 * constant, even if it does not correspond to an integer half-life. Note that this decay function will completely
 * ignore the travel time cutoff, and results will not change from one cutoff to another. For this reason we do not
 * expose this function as an easily selectable choice in the client UI.
 */
public class FixedExponentialDecayFunction extends DecayFunction {

    /**
     * Note that this is a decay constant for travel times in seconds.
     * If your decay constant is for travel times in minutes, you will need to divide it by 60.
     * Only positive values in the range (0, 1) are allowed to ensure decay (rather than growth or stagnation).
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
        return FastMath.exp(-decayConstant * travelTimeSeconds);
    }

    @Override
    public void prepare () {
        Preconditions.checkArgument(decayConstant > 0);
        Preconditions.checkArgument(decayConstant < 1);
    }

    @Override
    public int reachesZeroAt (int cutoffSeconds) {
        // This function is a bit strange in that it doesn't react to the cutoff parameter, so it doesn't respect
        // the otherwise intuitive constraint that it must reach zero at or above the cutoff. One option is to always
        // return the maximum value of two hours (since lower zero points are just an optimization). Another is to
        // compute the zero crossing once in the prepare() method, then always return that same value. But for the time
        // being we have just modified the default zero crossing finder to allow values lower than the cutoff.
        // return TWO_HOURS_IN_SECONDS;
        return super.reachesZeroAt(cutoffSeconds);
    }

}
