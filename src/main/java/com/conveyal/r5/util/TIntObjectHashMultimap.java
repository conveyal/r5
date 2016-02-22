package com.conveyal.r5.util;

import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.procedure.TIntObjectProcedure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Created by matthewc on 2/19/16.
 */
public class TIntObjectHashMultimap<V> implements TIntObjectMultimap<V> {
    private TIntObjectMap<List<V>> wrapped = new TIntObjectHashMap<>();

    @Override
    public boolean put(int key, V value) {
        if (!wrapped.containsKey(key)) wrapped.put(key, new ArrayList<>());
        return wrapped.get(key).add(value);
    }

    @Override
    public Collection<V> get(int key) {
        return wrapped.containsKey(key) ? wrapped.get(key) : Collections.emptyList();
    }

    @Override
    public void clear() {
        wrapped.clear();
    }

    @Override
    public boolean containsKey(int key) {
        return wrapped.containsKey(key);
    }

    @Override
    public void forEachEntry(TIntObjectProcedure<Collection<V>> procedure) {
        wrapped.forEachEntry(procedure);
    }

    @Override
    public int size() {
        return wrapped.size();
    }
}
