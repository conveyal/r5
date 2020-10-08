package com.conveyal.r5.trove;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * This List implementation wraps another existing List, and only allows extending it and retrieving values.
 */
public class AugmentedList<T> implements List<T> {

    List<T> base;

    List<T> extension;

    public AugmentedList(List<T> base) {
        this.base = base;
        this.extension = new ArrayList<>();
    }

    @Override
    public T get (int index) {
        if (index < base.size()) {
            return base.get(index);
        } else {
            return extension.get(index - base.size());
        }
    }

    @Override
    public T set (int index, T value) {
        if (index < base.size()) {
            throw new RuntimeException("Modifying the base graph is not allowed.");
        } else {
            return extension.set(index - base.size(), value);
        }
    }

    @Override
    public boolean add(T t) {
        return extension.add(t);
    }

    @Override
    public int size() {
        return base.size() + extension.size();
    }

    /* Nominally implement the List interface. All these methods will remain unimplemented until we need them. */

    @Override
    public void add(int index, T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public T remove(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int lastIndexOf(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<T> listIterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<T> subList(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<T> iterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object[] toArray() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T1> T1[] toArray(T1[] a) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }


}
