package com.conveyal.r5.diff;

import com.google.common.primitives.Longs;
import gnu.trove.map.TLongObjectMap;

/**
 * Adapts a Trove Long-Object hash map to the minimal common Map interface used in comparisons.
 *
 * Created by abyrd on 2018-11-02
 */
class TLongObjectMapWrapper extends MapComparisonWrapper {

    private TLongObjectMap map;

    public TLongObjectMapWrapper(TLongObjectMap map) {
        this.map = map;
    }

    @Override
    public Iterable<?> allKeys() {
        return Longs.asList(map.keys());
    }

    @Override
    public boolean containsKey(Object key) {
        return map.containsKey(((Number)key).longValue());
    }

    @Override
    public Object get(Object key) {
        // int casts to long, but Integer doesn't cast to Long. Do it manually to allow detecting no-entry value.
        // This is used to allow testing missing element values with the same key across different kinds of maps.
        return map.get(((Number)key).longValue());
    }

    @Override
    public int size() {
        return map.size();
    }

}
