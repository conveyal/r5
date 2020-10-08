package com.conveyal.r5.util;

import gnu.trove.TIntCollection;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;

import java.io.Serializable;

/**
 * Created by matthewc on 2/19/16.
 */
public class TIntIntHashMultimap implements TIntIntMultimap, Serializable {
    private long serialVersionUID = -1;

    private TIntObjectMap<TIntList> wrapped = new TIntObjectHashMap<>();

    @Override
    public boolean put(int key, int value) {
        if (!wrapped.containsKey(key)) wrapped.put(key, new TIntArrayList());
        return wrapped.get(key).add(value);
    }

    @Override
    public TIntCollection get(int key) {
        return wrapped.containsKey(key) ? wrapped.get(key) : EmptyTIntCollection.get();
    }

    @Override
    public boolean containsKey(int key) {
        return wrapped.containsKey(key);
    }

    @Override
    public TIntCollection removeAll(int key) {
        return wrapped.containsKey(key) ? wrapped.remove(key) : EmptyTIntCollection.get();
    }
}
