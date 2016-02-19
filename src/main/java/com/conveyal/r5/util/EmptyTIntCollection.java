package com.conveyal.r5.util;

import gnu.trove.TIntCollection;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.procedure.TIntProcedure;

import java.util.Collection;
import java.util.NoSuchElementException;

/**
 * An immutable, empty TIntCollction.
 */
public class EmptyTIntCollection implements TIntCollection {
    private static final EmptyTIntCollection instance = new EmptyTIntCollection();

    /** hide constructor, this is a singleton */
    protected EmptyTIntCollection () {}

    /** get an empty tintcollection */
    public static EmptyTIntCollection get () {
        return instance;
    }

    @Override
    public int getNoEntryValue() {
        return 0;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public boolean contains(int i) {
        return false;
    }

    @Override
    public TIntIterator iterator() {
        return new TIntIterator() {
            @Override
            public int next() {
                throw new NoSuchElementException();
            }

            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public void remove() {

            }
        };
    }

    @Override
    public int[] toArray() {
        return new int[0];
    }

    @Override
    public int[] toArray(int[] ints) {
        return new int[0];
    }

    @Override
    public boolean add(int i) {
        return false;
    }

    @Override
    public boolean remove(int i) {
        return false;
    }

    @Override
    public boolean containsAll(Collection<?> collection) {
        return false;
    }

    @Override
    public boolean containsAll(TIntCollection tIntCollection) {
        return false;
    }

    @Override
    public boolean containsAll(int[] ints) {
        return false;
    }

    @Override
    public boolean addAll(Collection<? extends Integer> collection) {
        return false;
    }

    @Override
    public boolean addAll(TIntCollection tIntCollection) {
        return false;
    }

    @Override
    public boolean addAll(int[] ints) {
        return false;
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        return false;
    }

    @Override
    public boolean retainAll(TIntCollection tIntCollection) {
        return false;
    }

    @Override
    public boolean retainAll(int[] ints) {
        return false;
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        return false;
    }

    @Override
    public boolean removeAll(TIntCollection tIntCollection) {
        return false;
    }

    @Override
    public boolean removeAll(int[] ints) {
        return false;
    }

    @Override
    public void clear() {

    }

    @Override
    public boolean forEach(TIntProcedure tIntProcedure) {
        return false;
    }
}
