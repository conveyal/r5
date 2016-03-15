package com.conveyal.r5.util;

import javafx.beans.NamedArg;
import javafx.util.Pair;

/**
 * Tuple of two elements with same type
 */
public class P2<E> extends Pair<E,E> {
    /**
     * Creates a new pair
     *
     * @param key   The key for this pair
     * @param value The value to use for this pair
     */
    public P2(
        @NamedArg("key")
        E key,
        @NamedArg("value")
        E value) {
        super(key, value);
    }
}
