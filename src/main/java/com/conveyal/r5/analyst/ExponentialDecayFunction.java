package com.conveyal.r5.analyst;

public class ExponentialDecayFunction extends DecayFunction {

    @Override
    public int reachesZeroAt (int cutoffSeconds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double computeWeight (int cutoffSeconds, int travelTimeSeconds) {
        throw new UnsupportedOperationException();
    }

}
