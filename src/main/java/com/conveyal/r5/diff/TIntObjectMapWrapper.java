package com.conveyal.r5.diff;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TLongObjectMap;

/**
 * Adapts a Trove int-Object hash map to the minimal common Map interface used in comparisons.
 *
 * Created by abyrd on 2018-11-05
 */
class TIntObjectMapWrapper extends MapComparisonWrapper {

    private TIntObjectMap map;

    public TIntObjectMapWrapper(TIntObjectMap map) {
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
    public Object get(Object key) {
        return map.get((int) key);
    }

}
