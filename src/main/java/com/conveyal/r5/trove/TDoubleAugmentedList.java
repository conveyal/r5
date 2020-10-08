package com.conveyal.r5.trove;

import gnu.trove.TDoubleCollection;
import gnu.trove.function.TDoubleFunction;
import gnu.trove.iterator.TDoubleIterator;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.procedure.TDoubleProcedure;

import java.util.Collection;
import java.util.Random;

/**
 * Created by abyrd on 2020-06-04
 */
public class TDoubleAugmentedList implements TDoubleList {

    private final TDoubleList base;

    private final TDoubleList extension;

    public TDoubleAugmentedList (TDoubleList base) {
        this.base = base;
        this.extension = new TDoubleArrayList();
    }

    @Override
    public double get (int index) {
        if (index < base.size()) {
            return base.get(index);
        } else {
            return extension.get(index - base.size());
        }
    }

    @Override
    public double set (int index, double value) {
        if (index < base.size()) {
            throw new RuntimeException("Modifying the base graph is not allowed.");
        } else {
            return extension.set(index - base.size(), value);
        }
    }

    @Override
    public boolean add(double val) {
        return extension.add(val);
    }

    @Override
    public int size() {
        return base.size() + extension.size();
    }

    /**
     *  Nominally implement the (enormous) TDoubleList interface.
     *  But all of these remain unimplemented until we need them.
     */

    @Override
    public double getNoEntryValue () {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty () {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add (double[] vals) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add (double[] vals, int offset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert (int offset, double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert (int offset, double[] values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert (int offset, double[] values, int valOffset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void set (int offset, double[] values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void set (int offset, double[] values, int valOffset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double replace (int offset, double val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear () {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove (double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double removeAt (int offset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void remove (int offset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void transformValues (TDoubleFunction function) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reverse () {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reverse (int from, int to) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void shuffle (Random rand) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TDoubleList subList (int begin, int end) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double[] toArray () {
        throw new UnsupportedOperationException();
    }

    @Override
    public double[] toArray (int offset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double[] toArray (double[] dest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double[] toArray (double[] dest, int offset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double[] toArray (double[] dest, int source_pos, int dest_pos, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean forEach (TDoubleProcedure procedure) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean forEachDescending (TDoubleProcedure procedure) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sort () {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sort (int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fill (double val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fill (int fromIndex, int toIndex, double val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int binarySearch (double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int binarySearch (double value, int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf (double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf (int offset, double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int lastIndexOf (double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int lastIndexOf (int offset, double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains (double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TDoubleList grep (TDoubleProcedure condition) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TDoubleList inverseGrep (TDoubleProcedure condition) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double max () {
        throw new UnsupportedOperationException();
    }

    @Override
    public double min () {
        throw new UnsupportedOperationException();
    }

    @Override
    public double sum () {
        throw new UnsupportedOperationException();
    }

    @Override
    public TDoubleIterator iterator () {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll (Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll (TDoubleCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll (double[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll (Collection<? extends Double> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll (TDoubleCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll (double[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll (Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll (TDoubleCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll (double[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll (Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll (TDoubleCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll (double[] array) {
        throw new UnsupportedOperationException();
    }
}
