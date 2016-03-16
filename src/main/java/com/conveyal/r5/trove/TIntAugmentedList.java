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
 * not the base list. The only methods that will access the base list and account for its indexes are get() and set().
 * The only methods that should be used to modify this list are add() and set().
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
            return super.get(index - startIndex);
        }
    }

    @Override
    public int set (int index, int value) {
        if (index < startIndex) {
            throw new RuntimeException("Modifying the base graph is not allowed.");
        } else {
            return super.set(index - startIndex, value);
        }
    }

    @Override
    public int size() {
        return base.size() + super.size();
    }

}