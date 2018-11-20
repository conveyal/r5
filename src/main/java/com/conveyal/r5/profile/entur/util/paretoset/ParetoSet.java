package com.conveyal.r5.profile.entur.util.paretoset;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Iterator;
import java.util.stream.Collectors;


/**
 * This {@link java.util.Collection} store all pareto-optimal elements. The
 * {@link #add(Object)} method returns {@code true} if and only if the element
 * was added successfully. When an element is added other elements witch are no
 * longer pareto-optimal are droped.
 * <p/>
 * Like the {@link java.util.ArrayList} the elements are stored internally in
 * an array for performance reasons, but the order is <em>not</em> guaranteed.
 * New elements can be added at any index - replacing the element at that index,
 * if the new element is dominates the old one.
 * <p/>
 * No methods for removing elements like {@link #remove(Object)} are supported.
 *
 * @param <T> the element type
 */
public class ParetoSet<T> extends AbstractCollection<T> {
    private final ParetoComparator<T> comparator;
    @SuppressWarnings("unchecked")
    private T[] elements = (T[])new Object[16];
    private int size = 0;


    public ParetoSet(ParetoComparator<T> comparator) {
        this.comparator = comparator;
    }

    public T get(int index) {
        return elements[index];
    }

    @Override
    public int size() {
        return size;
    }


    @Override
    @SuppressWarnings("NullableProblems")
    public Iterator<T> iterator() {
        return Arrays.stream(elements, 0, size).iterator();
    }

    @Override
    public boolean add(T  newValue) {
        if (size == 0) {
            appendValue(newValue);
            return true;
        }

        boolean mutualDominanceExist = false;
        boolean equivalentVectorExist = false;

        for (int i = 0; i < size; ++i) {
            T it = elements[i];

            boolean leftDominance = comparator.leftDominanceExist(newValue, it);
            boolean rightDominance = comparator.rightDominanceExist(newValue, it);

            if (leftDominance && rightDominance) {
                mutualDominanceExist = true;
            }
            else if (leftDominance) {
                elements[i] = newValue;
                removeDominatedElementsFromRestOfSet(newValue, i+1);
                return true;
            }
            else if (rightDominance) {
                return false;
            }
            else {
                equivalentVectorExist = true;
            }
        }

        if (mutualDominanceExist && !equivalentVectorExist) {
            assertEnoughSpaceInSet();
            appendValue(newValue);
            return true;
        }

        // No dominance found, newValue is equivalent with all values in the set
        return false;
    }

    /**
     * Test if an element qualify - the element is NOT added. Use the {@link #add(T)}
     * method directly if the purpose is to add the new element to the collection.
     * <p/>
     * Both methods are optimized for performance; hence the add method does not use this method.
     */
    public boolean qualify(T  newValue) {
        if (size == 0) {
            return true;
        }

        boolean mutualDominanceExist = false;
        boolean equivalentVectorExist = false;

        for (int i = 0; i < size; ++i) {
            boolean leftDominance = comparator.leftDominanceExist(newValue, elements[i]);
            boolean rightDominance = comparator.rightDominanceExist(newValue, elements[i]);


            if (leftDominance && rightDominance) {
                if(equivalentVectorExist) {
                    return false;
                }
                mutualDominanceExist = true;
            }
            else if (leftDominance) {
                return true;
            }
            else if (rightDominance) {
                return false;
            }
            else {
                if(mutualDominanceExist) {
                    return false;
                }
                equivalentVectorExist = true;
            }
        }
        return mutualDominanceExist;
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        size = 0;
    }

    /**
     * This is used for logging and tuning purposes - by looking at the statistics we can decide
     * a good value for the initial size.
     */
    public int elementArrayLen() {
        return elements.length;
    }

    @Override
    public String toString() {
        return "{" + Arrays.stream(elements, 0, size)
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(", ")) + "}";
    }

    /**
     * Remove all elements dominated by the {@code newValue} starting from
     * index {@code index}.
     */
    private void removeDominatedElementsFromRestOfSet(final T newValue, final int index) {
        // Let 'i' be the current element index
        int i = index;
        // Be aware that the index method parameter is incremented
        while (i < size) {
            // Find an element to skip, and move the last element into its position
            // Note! We can not advance the index `i`, because last element must also
            // be checked.
            if (leftVectorDominatesRightVector(newValue, elements[i])) {
                --size;
                elements[i] = elements[size];
                elements[size] = null;
            }
            else {
                ++i;
            }
        }
    }

    private boolean leftVectorDominatesRightVector(T left, T right) {
        return comparator.leftDominanceExist(left, right) &&
                !comparator.rightDominanceExist(left, right);
    }

    private void appendValue(T newValue) {
        elements[size++] = newValue;
    }

    private void assertEnoughSpaceInSet() {
        if (size == elements.length) {
            elements = Arrays.copyOf(elements, elements.length * 2);
        }
    }
}
