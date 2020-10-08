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
 */
public class TIntAugmentedList implements TIntList {

    private final TIntList base;

    private final TIntList extension;

    public TIntAugmentedList(TIntList base) {
        this.base = base;
        this.extension = new TIntArrayList();
    }

    @Override
    public int get (int index) {
        if (index < base.size()) {
            return base.get(index);
        } else {
            return extension.get(index - base.size());
        }
    }

    @Override
    public int set (int index, int value) {
        if (index < base.size()) {
            throw new RuntimeException("Modifying the base graph is not allowed.");
        } else {
            return extension.set(index - base.size(), value);
        }
    }

    @Override
    public boolean add(int val) {
        return extension.add(val);
    }

    @Override
    public int size() {
        return base.size() + extension.size();
    }

    /**
     *  Nominally implement the (enormous) TIntList interface.
     *  But all of these remain unimplemented until we need them.
     */

    @Override
    public void set(int offset, int[] values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void set(int offset, int[] values, int valOffset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int replace(int offset, int val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(TIntCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(int[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(Collection<? extends Integer> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(TIntCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(int[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(TIntCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(int[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(TIntCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(int[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int removeAt(int offset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void remove(int offset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void transformValues(TIntFunction function) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reverse() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reverse(int from, int to) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void shuffle(Random rand) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TIntList subList(int begin, int end) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int[] toArray() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int[] toArray(int offset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int[] toArray(int[] dest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int[] toArray(int[] dest, int offset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int[] toArray(int[] dest, int source_pos, int dest_pos, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean forEach(TIntProcedure procedure) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean forEachDescending(TIntProcedure procedure) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sort() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sort(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fill(int val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fill(int fromIndex, int toIndex, int val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int binarySearch(int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int binarySearch(int value, int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(int offset, int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int lastIndexOf(int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int lastIndexOf(int offset, int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TIntIterator iterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public TIntList grep(TIntProcedure condition) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TIntList inverseGrep(TIntProcedure condition) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int max() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int min() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int sum() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getNoEntryValue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(int[] vals) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(int[] vals, int offset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert(int offset, int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert(int offset, int[] values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert(int offset, int[] values, int valOffset, int len) {
        throw new UnsupportedOperationException();
    }

}