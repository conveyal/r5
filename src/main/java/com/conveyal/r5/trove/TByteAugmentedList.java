package com.conveyal.r5.trove;

import gnu.trove.TByteCollection;
import gnu.trove.function.TByteFunction;
import gnu.trove.iterator.TByteIterator;
import gnu.trove.list.TByteList;
import gnu.trove.list.array.TByteArrayList;
import gnu.trove.procedure.TByteProcedure;

import java.util.Collection;
import java.util.Random;

/**
 * FIXME we now have four TXAugmentedList classes. This is manual templating of primitive generics which is pretty
 *       easy to write but might be a real problem to maintain.
 */
public class TByteAugmentedList implements TByteList {

    private final TByteList base;

    private final TByteList extension;

    public TByteAugmentedList (TByteList base) {
        this.base = base;
        this.extension = new TByteArrayList();
    }

    @Override
    public byte get (int index) {
        if (index < base.size()) {
            return base.get(index);
        } else {
            return extension.get(index - base.size());
        }
    }

    @Override
    public byte set (int index, byte value) {
        if (index < base.size()) {
            throw new RuntimeException("Modifying the base graph is not allowed.");
        } else {
            return extension.set(index - base.size(), value);
        }
    }

    @Override
    public boolean add (byte val) {
        return extension.add(val);
    }

    @Override
    public int size() {
        return base.size() + extension.size();
    }


    /**
     *  Nominally implement the (enormous) TByteList interface.
     *  But all of these remain unimplemented until we need them.
     */

    @Override
    public byte getNoEntryValue () {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty () {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add (byte[] vals) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add (byte[] vals, int offset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert (int offset, byte value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert (int offset, byte[] values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insert (int offset, byte[] values, int valOffset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void set (int offset, byte[] values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void set (int offset, byte[] values, int valOffset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte replace (int offset, byte val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear () {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove (byte value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll (Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll (TByteCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll (byte[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll (Collection<? extends Byte> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll (TByteCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll (byte[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll (Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll (TByteCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll (byte[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll (Collection<?> collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll (TByteCollection collection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll (byte[] array) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte removeAt (int offset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void remove (int offset, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void transformValues (TByteFunction function) {
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
    public TByteList subList (int begin, int end) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] toArray () {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] toArray (int offset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] toArray (byte[] dest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] toArray (byte[] dest, int offset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] toArray (byte[] dest, int source_pos, int dest_pos, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean forEach (TByteProcedure procedure) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean forEachDescending (TByteProcedure procedure) {
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
    public void fill (byte val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fill (int fromIndex, int toIndex, byte val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int binarySearch (byte value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int binarySearch (byte value, int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf (byte value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf (int offset, byte value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int lastIndexOf (byte value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int lastIndexOf (int offset, byte value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains (byte value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TByteIterator iterator () {
        throw new UnsupportedOperationException();
    }

    @Override
    public TByteList grep (TByteProcedure condition) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TByteList inverseGrep (TByteProcedure condition) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte max () {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte min () {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte sum () {
        throw new UnsupportedOperationException();
    }
}
