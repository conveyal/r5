package com.conveyal.r5.diff;

import com.google.common.primitives.Ints;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;

/**
 * Adapts a Trove int-int map to the minimal common Map interface used in comparisons.
 * TODO tests on this and other Trove and Guava comparison adapters
 *
 * Created by abyrd on 2018-11-05
 */
class TIntIntMapWrapper extends MapComparisonWrapper {

    private TIntIntMap map;

    public TIntIntMapWrapper(TIntIntMap map) {
        this.map = map;
    }

    @Override
    public Iterable<Integer> allKeys() {
        return Ints.asList(map.keys());
    }

    @Override
    public boolean containsKey(Object key) {
        return map.containsKey((int) key);
    }

    @Override
    public Integer get(Object key) {
        return map.get((int) key);
    }

}
