package com.conveyal.r5.profile.otp2.rangeraptor.transit;

import java.util.BitSet;


/**
 * A implementation of the StopFilter interface that wraps a BitSet.
 */
public class StopFilerBitSet implements StopFilter {
    private final BitSet allowVisit;

    StopFilerBitSet(BitSet allowVisit) {
        this.allowVisit = allowVisit;
    }

    @Override
    public boolean allowStopVisit(int stop) {
        return allowVisit.get(stop);
    }
}
