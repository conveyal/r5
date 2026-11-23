package com.conveyal.r5.util;

import gnu.trove.procedure.TIntObjectProcedure;
import gnu.trove.procedure.TIntProcedure;

import java.util.Collection;
import java.util.List;

/**
 * A map from primitive int keys to object instances of a generic type.
 */
public interface TIntObjectMultimap<V> {
    boolean put (int key, V value);
    /// Always returns a non-null collection, which may be empty.
    Collection<V> get (int key);
    void clear();
    boolean containsKey (int key);
    boolean forEachKey (TIntProcedure procedure);
    boolean forEachEntry (TIntObjectProcedure<Collection<V>> procedure);
    boolean retainEntries (TIntObjectProcedure<Collection<V>> procedure);

    /** number of keys */
    int size();
}
