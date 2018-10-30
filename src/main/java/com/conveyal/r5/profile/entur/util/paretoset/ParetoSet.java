package com.conveyal.r5.profile.entur.util.paretoset;

import java.util.Arrays;
import java.util.Iterator;
import java.util.stream.Collectors;


public class ParetoSet<T extends ParetoSortable> implements Iterable<T> {
    private final ParetoVectorDominator dominator;
    private ParetoSortable[] paretoSet = new ParetoSortable[16];
    private int size = 0;


    public ParetoSet(ParetoFunction.Builder builder) {
        this.dominator = ParetoVectorDominator.create(builder.build());
    }

    public void clear() {
        size = 0;
    }

    public boolean add(T newValue) {

        if (size == 0) {
            appendValue(newValue);
            return true;
        }

        boolean mutualDominanceExist = false;
        boolean equivalentVectorExist = false;

        int i = 0;

        for (; i < size; ++i) {
            ParetoSortable it = paretoSet[i];

            dominator.dominate(newValue, it);

            if (dominator.mutualVectorDominantesExist()) {
                mutualDominanceExist = true;
            }
            else if (dominator.newCriteriaDominatesExist()) {
                paretoSet[i] = newValue;
                removeDominatedElementsFromRestOfSet(newValue, i+1);
                return true;
            }
            else if (dominator.existingCriteriaDominanceExist()) {
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

    public boolean isEmpty() {
        return size == 0;
    }

    public T get(int index) {
        return (T) paretoSet[index];
    }

    public int size() {
        return size;
    }
    public int memUsed() {
        return paretoSet.length;
    }

    @Override
    public String toString() {
        return "{" + Arrays.stream(paretoSet, 0, size)
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(", ")) + "}";
    }

    @Override
    public Iterator<T> iterator() {
        return (Iterator<T>) Arrays.stream(paretoSet, 0, size).iterator();
    }

    /**
     * Remove all elements dominated by the {@code newValue} starting from
     * index {@code index}.
     */
    private void removeDominatedElementsFromRestOfSet(final T newValue, int index) {
        // Be aware that the index method parameter is incremented
        while (index < size) {
            dominator.dominate(newValue, paretoSet[index]);

            // Find an element to skip, and move the last element into its position
            // Note! We can not advance the index `i`, because last element must also
            // be checked.
            if (dominator.newVectorDominatesExistingVector()) {
                --size;
                paretoSet[index] = paretoSet[size];
            }
            else {
                ++index;
            }
        }
    }

    private void appendValue(T newValue) {
        paretoSet[size++] = newValue;
    }

    private void assertEnoughSpaceInSet() {
        if (size == paretoSet.length) {
            paretoSet = Arrays.copyOf(paretoSet, paretoSet.length * 2);
        }
    }
}
