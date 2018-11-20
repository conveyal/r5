package com.conveyal.r5.profile.entur.util.paretoset;

@FunctionalInterface
public interface ParetoComparator<T> {

    /**
     * At least one of the left criteria dominates one of the corresponding right criteria.
     */
    boolean leftDominanceExist(T left, T right);


    default boolean rightDominanceExist(T left, T right) {
        return leftDominanceExist(right, left);
    }
}
