package com.conveyal.r5.profile.entur.util;

import java.util.ArrayList;
import java.util.List;


public class ParetoSet<T extends ParetoSortable> {
    private final int paretoVectorSize;
    private final ParetoDominanceFunction[] dominateFunctions;
    private List<T> paretoSet = new ArrayList<>();

    public ParetoSet(ParetoDominanceFunctions.Builder builder) {
        this.dominateFunctions = builder.build();
        this.paretoVectorSize = dominateFunctions.length;
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

    boolean leftDominatesRight = false;
    boolean rightDominatesLeft = false;

    private Domainace dominate(ParetoSortable lhs, ParetoSortable rhs) {
        leftDominatesRight = false;
        rightDominatesLeft = false;

        if(paretoCompare(dominateFunctions[0], lhs.paretoValue1(), rhs.paretoValue1())) {
            return Domainace.Equal;
        }
        if(paretoVectorSize == 1) {
            return paretoResult();
        }

        if(paretoCompare(dominateFunctions[1], lhs.paretoValue2(), rhs.paretoValue2())) {
            return Domainace.Equal;
        }
        if(paretoVectorSize == 2) {
            return paretoResult();
        }

        if(paretoCompare(dominateFunctions[2], lhs.paretoValue3(), rhs.paretoValue3())) {
            return Domainace.Equal;
        }
        if(paretoVectorSize == 3) {
            return paretoResult();
        }

        if(paretoCompare(dominateFunctions[3], lhs.paretoValue4(), rhs.paretoValue4())) {
            return Domainace.Equal;
        }
        if(paretoVectorSize == 4) {
            return paretoResult();
        }

        if(paretoCompare(dominateFunctions[4], lhs.paretoValue5(), rhs.paretoValue5())) {
            return Domainace.Equal;
        }
        if(paretoVectorSize == 5) {
            return paretoResult();
        }

        if(paretoCompare(dominateFunctions[5], lhs.paretoValue6(), rhs.paretoValue6())) {
            return Domainace.Equal;
        }
        if(paretoVectorSize == 6) {
            return paretoResult();
        }

        if(paretoCompare(dominateFunctions[6], lhs.paretoValue7(), rhs.paretoValue7())) {
            return Domainace.Equal;
        }
        if(paretoVectorSize == 7) {
            return paretoResult();
        }

        if(paretoCompare(dominateFunctions[7], lhs.paretoValue8(), rhs.paretoValue8())) {
            return Domainace.Equal;
        }
        return paretoResult();

/*
        for (int i = 0; i < paretoVectorSize; ++i) {
            int left = value(lhs, i);
            int right = value(rhs, i);
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
        */
    }

    private boolean paretoCompare(ParetoDominanceFunction func, int left, int right) {
        if (func.mutualDominance(left, right)) {
            return true;
        }
        if (func.dominates(left, right)) {
            if (rightDominatesLeft) {
                return true;
            }
            leftDominatesRight = true;
        } else if (func.dominates(right, left)) {
            if (leftDominatesRight) {
                return true;
            }
            rightDominatesLeft = true;
        }
        return false;
    }

    private Domainace paretoResult() {
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
