package com.conveyal.r5.analyst.decay;

import org.bson.codecs.pojo.annotations.BsonDiscriminator;

/**
 * Simple cliff-edge weight function. No parameters to set or validate.
 */
@BsonDiscriminator(value = "step", key = "type")
public class StepDecayFunction extends DecayFunction {

    @Override
    public void prepare() {
        // Nothing to validate or prepare.
    }

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
