package com.conveyal.r5.profile.entur.util.paretoset;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Iterator;
import java.util.stream.Collectors;


public class ParetoSet<T extends ParetoSortable> extends AbstractCollection<T> {
    private final ParetoVectorDominator dominator;
    @SuppressWarnings("unchecked")
    private T[] elements = (T[])new ParetoSortable[16];
    private int size = 0;


    public ParetoSet(ParetoFunction[] builder) {
        this.dominator = ParetoVectorDominator.create(builder);
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

        int i = 0;

        for (; i < size; ++i) {
            ParetoSortable it = elements[i];

            dominator.dominate(newValue, it);

            if (dominator.mutualVectorDominanceExist()) {
                mutualDominanceExist = true;
            }
            else if (dominator.leftCriteriaDominanceExist()) {
                elements[i] = newValue;
                removeDominatedElementsFromRestOfSet(newValue, i+1);
                return true;
            }
            else if (dominator.rightCriteriaDominanceExist()) {
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
     * Test if an element qualify - the element is NOT added. Use the {@link #add(ParetoSortable)}
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

        int i = 0;

        for (; i < size; ++i) {
            dominator.dominate(newValue, elements[i]);

            if (dominator.mutualVectorDominanceExist()) {
                if(equivalentVectorExist) {
                    return false;
                }
                mutualDominanceExist = true;
            }
            else if (dominator.leftCriteriaDominanceExist()) {
                return true;
            }
            else if (dominator.rightCriteriaDominanceExist()) {
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
    private void removeDominatedElementsFromRestOfSet(final T newValue, int index) {
        // Be aware that the index method parameter is incremented
        while (index < size) {
            dominator.dominate(newValue, elements[index]);

            // Find an element to skip, and move the last element into its position
            // Note! We can not advance the index `i`, because last element must also
            // be checked.
            if (dominator.leftVectorDominatesRightVector()) {
                --size;
                elements[index] = elements[size];
                elements[size] = null;
            }
            else {
                ++index;
            }
        }
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
