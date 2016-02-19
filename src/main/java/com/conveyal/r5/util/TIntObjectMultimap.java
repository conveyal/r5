package com.conveyal.r5.util;

import gnu.trove.procedure.TIntObjectProcedure;

import java.util.Collection;

/**
 * A primitive multimap
 */
public interface TIntObjectMultimap<V> {
    boolean put (int key, V value);
    Collection<V> get (int key);
    void clear();
    boolean containsKey (int key);
    void forEachEntry (TIntObjectProcedure<Collection<V>> procedure);

    /** number of keys */
    int size();
}
