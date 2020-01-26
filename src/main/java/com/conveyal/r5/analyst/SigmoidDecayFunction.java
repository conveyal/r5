package com.conveyal.r5.analyst;

public class SigmoidDecayFunction extends DecayFunction {

    public int standardDeviationSeconds;

    @Override
    public int reachesZeroAt (int cutoffSeconds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double computeWeight (int cutoffSeconds, int travelTimeSeconds) {
        throw new UnsupportedOperationException();
    }

}
