package com.conveyal.r5.profile.entur.util.paretoset;

import java.util.ArrayList;
import java.util.List;


public class ParetoSet<T extends ParetoSortable> {
    private final List<T> paretoSet = new ArrayList<>();
    private final ParetoVectorDominator dominator;


    public ParetoSet(ParetoDominanceFunctions.Builder builder) {
        this.dominator = ParetoVectorDominator.create(builder.build());
    }

    public boolean add(T newValue) {
        List<T> discard = new ArrayList<>();

        for (T it : paretoSet) {
            switch (dominator.dominate(it, newValue)) {
                case LeftDominatesRight:
                    return false;
                case RightDominatesLeft:
                    discard.add(it);
                    break;
                case MutualDominance:
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

    public int size() {
        return paretoSet.size();
    }
}
