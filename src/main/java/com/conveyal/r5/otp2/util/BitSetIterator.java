package com.conveyal.r5.otp2.util;

import com.conveyal.r5.otp2.api.transit.IntIterator;

import java.util.BitSet;

/**
 * TODO TGR
 */
public final class BitSetIterator implements IntIterator {

    private final BitSet set;
    private int nextIndex;

    public BitSetIterator(BitSet set) {
        this.set = set;
        this.nextIndex = set.nextSetBit(nextIndex);
    }

    @Override
    public final int next() {
        int index = nextIndex;
        nextIndex = set.nextSetBit(index + 1);
        return index;
    }

    @Override
    public final boolean hasNext() {
        return nextIndex != -1;
    }
}