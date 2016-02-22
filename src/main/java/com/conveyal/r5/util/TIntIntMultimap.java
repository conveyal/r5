package com.conveyal.r5.util;

import gnu.trove.TIntCollection;

/**
 * Created by matthewc on 2/19/16.
 */
public interface TIntIntMultimap {
    boolean put (int key, int value);
    TIntCollection get (int key);
    boolean containsKey (int key);
    TIntCollection removeAll (int key);
}
