package com.conveyal.r5.trove;

import gnu.trove.TIntCollection;
import gnu.trove.function.TIntFunction;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.procedure.TIntProcedure;

import java.util.Collection;
import java.util.Random;

/**
 * This TIntArrayList extension wraps another TIntList, and only allows extending it and retrieving values.
 * It assumes the wrapped TIntList is immutable and treats it as such.
 * NOTE that most methods will not behave as expected, as they will affect or see only the augmented part of the list,
 * not the base list. The only method that will access the base list and account for its indexes is get(). The only
 * method that should be used to modify this list is add().
 *
 * We should not resize edges when splitting edges to link stops, because this doesn't work when temporarily adding
 * stops.
 */
public class TIntAugmentedList extends TIntArrayList {

    TIntList base;

    int startIndex;

    public TIntAugmentedList(TIntList base) {
        super();
        this.base = base;
        this.startIndex = base.size();
    }

    @Override
    public int get (int index) {
        if (index < startIndex) {
            return base.get(index);
        } else {
            index -= startIndex;
            return this.get(index);
        }
    }

}