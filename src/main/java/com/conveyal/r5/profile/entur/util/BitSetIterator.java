package com.conveyal.r5.profile.entur.util;

import java.util.BitSet;

public class BitSetIterator {

    private final BitSet set;
    private int nextIndex = 0;

    public BitSetIterator(BitSet set) {
        this.set = set;
    }

    public int next() {
        int index = set.nextSetBit(nextIndex);
        nextIndex = index + 1;
        return index;
    }
}
