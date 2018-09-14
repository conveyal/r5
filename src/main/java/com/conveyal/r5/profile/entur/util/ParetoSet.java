package com.conveyal.r5.profile.entur.util;

import java.util.ArrayList;
import java.util.List;


public class ParetoSet<T extends ParetoSortable> {

    private final ParetoDominanceFunction[] dominateFunctions;
    private List<T> paretoSet = new ArrayList<>();

    public ParetoSet(ParetoDominanceFunctions.Builder builder) {
        this.dominateFunctions = builder.build();
    }

    public boolean add(T newValue) {
        List<T> discard = new ArrayList<>();

        for (T it : paretoSet) {
            switch (dominate(it, newValue)) {
                case LeftDominatesRight:
                    return false;
                case RightDominatesLeft:
                    discard.add(it);
                    break;
                case Equal:
                    break;
            }
        }
        paretoSet.add(newValue);
        paretoSet.removeAll(discard);
        return true;
    }

    public Iterable<T> paretoSet() {
        return paretoSet;
    }

    public boolean isEmpty() {
        return paretoSet.isEmpty();
    }

    public T get(int index) {
        return paretoSet.get(index);
    }

    private Domainace dominate(ParetoSortable lhs, ParetoSortable rhs) {
        final int size = lhs.paretoValues().length;
        final int[] l = lhs.paretoValues();
        final int[] r = rhs.paretoValues();

        boolean leftDominatesRight = false;
        boolean rightDominatesLeft = false;


        for (int i = 0; i < size; ++i) {
            int left = l[i];
            int right = r[i];
            ParetoDominanceFunction func = dominateFunctions[i];

            if (func.mutualDominance(left, right)) {
                return Domainace.Equal;
            }
            if (dominateFunctions[i].dominates(left, right)) {
                if (rightDominatesLeft) {
                    return Domainace.Equal;
                }
                leftDominatesRight = true;
            } else if (dominateFunctions[i].dominates(right, left)) {
                if (leftDominatesRight) {
                    return Domainace.Equal;
                }
                rightDominatesLeft = true;
            }
            // else l[i] == r[i]
        }
        if (leftDominatesRight) return Domainace.LeftDominatesRight;
        if (rightDominatesLeft) return Domainace.RightDominatesLeft;
        return Domainace.Equal;
    }

    public int size() {
        return paretoSet.size();
    }

    enum Domainace {
        LeftDominatesRight,
        RightDominatesLeft,
        Equal
    }
}
