package com.conveyal.r5.analyst;

/**
 * Simple cliff-edge weight function.
 */
public class StepDecayFunction extends DecayFunction {

    @Override
    public int reachesZeroAt (int cutoffSeconds) {
        return cutoffSeconds;
    }

    @Override
    public double computeWeight (int cutoffSeconds, int travelTimeSeconds) {
        if (travelTimeSeconds < cutoffSeconds) {
            return 1;
        } else {
            return 0;
        }
    }

}
