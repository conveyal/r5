package com.conveyal.r5.util;

import gnu.trove.TIntCollection;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;

/// Alternative implementation of TIntIntMultimap with set semantics for the values per key.
/// Get and remove methods have been altered from usual Trove patterns to always return a
/// non-null collection (empty in place of null collections). Does not implement the existing
/// TIntIntMultimap interface because I wanted to add an efficient addAll method.
public class TIntIntHashSetMultimap {

    private final TIntObjectMap<TIntSet> map = new TIntObjectHashMap<>();

    public boolean put(int key, int value) {
        TIntSet set = map.get(key);
        if (set == null) {
            set = new TIntHashSet();
            map.put(key, set);
        }
        return set.add(value);
    }

    /// Associate all elements of the supplied values collection with the given key.
    /// Proper handling for null or empty values.
    public boolean putAll(int key, TIntCollection values) {
        if (values == null || values.isEmpty()) return false;
        TIntSet set = map.get(key);
        if (set == null) {
            set = new TIntHashSet();
            map.put(key, set);
        }
        return set.addAll(values);
    }

    public TIntCollection get(int key) {
        TIntSet set = map.get(key);
        return (set == null) ? EmptyTIntCollection.get() : set;
    }

    public boolean containsKey(int key) {
        return map.containsKey(key);
    }

    public TIntCollection removeAll(int key) {
        TIntSet set = map.remove(key);
        return (set == null) ? EmptyTIntCollection.get() : set;
    }
}
