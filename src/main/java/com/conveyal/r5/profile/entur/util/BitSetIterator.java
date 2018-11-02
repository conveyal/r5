package com.conveyal.r5.profile.entur.util;

import com.conveyal.r5.profile.entur.api.UnsignedIntIterator;

import java.util.BitSet;

public class BitSetIterator implements UnsignedIntIterator {

    private final BitSet set;
    private int nextIndex = 0;

    public BitSetIterator(BitSet set) {
        this.set = set;
    }

    @Override
    public int next() {
        int index = set.nextSetBit(nextIndex);
        nextIndex = index + 1;
        return index;
    }
}
