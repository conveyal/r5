package com.conveyal.r5.trove;

import gnu.trove.TLongCollection;
import gnu.trove.function.TLongFunction;
import gnu.trove.iterator.TLongIterator;
import gnu.trove.list.TLongList;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.procedure.TLongProcedure;

import java.util.Collection;
import java.util.Random;

/**
 * This TLongArrayList extension wraps another TLongList, and only allows extending it and retrieving values.
 * It assumes the wrapped TIntList is immutable and treats it as such.
 */
public class TLongAugmentedList implements TLongList {

    private final TLongList base;

    private final TLongList extension;

    public TLongAugmentedList(TLongList base) {
        this.base = base;
        this.extension = new TLongArrayList();
    }

    @Override
    public long get (int index) {
        if (index < base.size()) {
            return base.get(index);
        } else {
            return extension.get(index - base.size());
        }
    }

    @Override
    public long set (int index, long value) {
        if (index < base.size()) {
            throw new RuntimeException("Modifying the base graph is not allowed.");
        } else {
            return extension.set(index - base.size(), value);
        }
    }

    @Override
    public boolean add(long val) {
        return extension.add(val);
    }

    @Override
    public int size() {
        return base.size() + extension.size();
    }


    /**
     *  Nominally implement the (enormous) TLongList interface.
     *  But all of these remain unimplemented until we need them.
     */

    @Override
    public void set(int offset, long[] values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void set(int offset, long[] values, int valOffset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long replace(int offset, long val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(long value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(TLongCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(long[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(Collection<? extends Long> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(TLongCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(long[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(TLongCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(long[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(TLongCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(long[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long removeAt(int offset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void remove(int offset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void transformValues(TLongFunction function) {
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
    public TLongList subList(int begin, int end) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long[] toArray() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long[] toArray(int offset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long[] toArray(long[] dest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long[] toArray(long[] dest, int offset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long[] toArray(long[] dest, int source_pos, int dest_pos, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean forEach(TLongProcedure procedure) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean forEachDescending(TLongProcedure procedure) {
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
    public void fill(long val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fill(int fromIndex, int toIndex, long val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int binarySearch(long value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int binarySearch(long value, int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(long value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(int offset, long value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int lastIndexOf(long value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int lastIndexOf(int offset, long value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(long value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TLongIterator iterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public TLongList grep(TLongProcedure condition) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TLongList inverseGrep(TLongProcedure condition) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long max() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long min() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long sum() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getNoEntryValue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(long[] vals) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(long[] vals, int offset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert(int offset, long value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert(int offset, long[] values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert(int offset, long[] values, int valOffset, int len) {
        throw new UnsupportedOperationException();
    }

}