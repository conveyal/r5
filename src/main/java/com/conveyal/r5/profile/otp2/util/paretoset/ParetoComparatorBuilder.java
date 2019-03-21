package com.conveyal.r5.profile.otp2.util.paretoset;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;


/**
 * Utility builder class to help implement {@link ParetoComparator}. The builder try to
 * build an efficient comparator, but if performance is realy important it might
 * be better to implement it your self.
 *
 * @param <T> The type of the pareto set element passed to the comparator.
 */
public final class ParetoComparatorBuilder<T> {
    private final List<BiPredicate<T, T>> valueSuppliers = new ArrayList<>();

    public ParetoComparatorBuilder<T> lessThen(ToIntFunction<T> supplier) {
        valueSuppliers.add((l, r) -> supplier.applyAsInt(l) < supplier.applyAsInt(r));
        return this;
    }

    public ParetoComparatorBuilder<T> greaterThen(ToIntFunction<T> supplier) {
        valueSuppliers.add((l, r) -> supplier.applyAsInt(l) > supplier.applyAsInt(r));
        return this;
    }

    public ParetoComparatorBuilder<T> different(ToIntFunction<T> supplier) {
        valueSuppliers.add((l, r) -> supplier.applyAsInt(l) != supplier.applyAsInt(r));
        return this;
    }

    public ParetoComparatorBuilder<T> lessThenDelta(ToIntFunction<T> supplier, final int delta) {
        valueSuppliers.add((l, r) -> supplier.applyAsInt(l) + delta < supplier.applyAsInt(r));
        return this;
    }

    public ParetoComparator<T> build() {

        // Create different builders for different size vectors, this improve
        // performance
        switch (valueSuppliers.size()) {
            case 1:
                return new ParetoComparator<T>() {
                    private BiPredicate<T, T> comparator = valueSuppliers.get(0);

                    @Override
                    public boolean leftDominanceExist(T left, T right) {
                        return comparator.test(left, right);
                    }
                };
            case 2:
                return new ParetoComparator<T>() {
                    private BiPredicate<T, T> c1 = valueSuppliers.get(0);
                    private BiPredicate<T, T> c2 = valueSuppliers.get(1);

                    @Override
                    public boolean leftDominanceExist(T left, T right) {
                        return c1.test(left, right) || c2.test(left, right);
                    }
                };
            case 3:
                return new ParetoComparator<T>() {
                    private BiPredicate<T, T> c1 = valueSuppliers.get(0);
                    private BiPredicate<T, T> c2 = valueSuppliers.get(1);
                    private BiPredicate<T, T> c3 = valueSuppliers.get(2);

                    @Override
                    public boolean leftDominanceExist(T left, T right) {
                        return c1.test(left, right) || c2.test(left, right) || c3.test(left, right);
                    }
                };
            case 4:
                return new ParetoComparator<T>() {
                    private BiPredicate<T, T> c1 = valueSuppliers.get(0);
                    private BiPredicate<T, T> c2 = valueSuppliers.get(1);
                    private BiPredicate<T, T> c3 = valueSuppliers.get(2);
                    private BiPredicate<T, T> c4 = valueSuppliers.get(3);

                    @Override
                    public boolean leftDominanceExist(T left, T right) {
                        return c1.test(left, right) || c2.test(left, right) ||
                                c3.test(left, right) || c4.test(left, right);
                    }
                };
            case 5:
                return new ParetoComparator<T>() {
                    private BiPredicate<T, T> c1 = valueSuppliers.get(0);
                    private BiPredicate<T, T> c2 = valueSuppliers.get(1);
                    private BiPredicate<T, T> c3 = valueSuppliers.get(2);
                    private BiPredicate<T, T> c4 = valueSuppliers.get(3);
                    private BiPredicate<T, T> c5 = valueSuppliers.get(4);

                    @Override
                    public boolean leftDominanceExist(T left, T right) {
                        return c1.test(left, right) || c2.test(left, right) || c3.test(left, right) ||
                                c4.test(left, right) || c5.test(left, right);
                    }
                };
            default:
        //noinspection unchecked
        return new ParetoComparator<T>() {
                    private BiPredicate<T, T>[] comparators = (BiPredicate<T, T>[]) valueSuppliers.toArray(new BiPredicate[0]);

                    @Override
                    public boolean leftDominanceExist(T left, T right) {
                        for (BiPredicate<T, T> comparator : comparators) {
                            if (comparator.test(left, right)) {
                                return true;
                            }
                        }
                        return false;
                    }
                };
        }
    }
}
