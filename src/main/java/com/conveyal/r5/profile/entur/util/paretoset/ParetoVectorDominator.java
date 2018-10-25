package com.conveyal.r5.profile.entur.util.paretoset;


/**
 * This is an optimized class for calulating doniminance between to pareto vectors.
 * <p/>
 * The class is higly optimized for performence. For example, by changing the code
 * to perform one single `if` less increased the RR perormance by many percent.
 * <p/>
 * THIS CLASS IS *NOT* THREAD SAFE.
 * <p/>
 */
abstract class ParetoVectorDominator {
    final InternalParetoDominanceFunction[] dominateFunctions;

    /** Temporary storage for the calculation state - not thread safe. */
    private boolean leftDominatesRight;

    /** Temporary storage for the calculation state - not thread safe. */
    private boolean rightDominatesLeft;

    private ParetoVectorDominator(InternalParetoDominanceFunction[] dominateFunctions) {
        this.dominateFunctions = dominateFunctions;
    }


    /**
     * Factory method to create a dominator using the exact vector size depending on the
     * <code>dominateFunctions</code> size.
     */
    static ParetoVectorDominator create(final InternalParetoDominanceFunction[] dominateFunctions) {
        switch (dominateFunctions.length) {
            case 1 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                boolean doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    return paretoCompare(dominateFunctions[0], lhs.paretoValue1(), rhs.paretoValue1());
                }
            };
            case 2 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                boolean doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    return paretoCompare(dominateFunctions[0], lhs.paretoValue1(), rhs.paretoValue1())
                        || paretoCompare(dominateFunctions[1], lhs.paretoValue2(), rhs.paretoValue2())
                        ;
                }
            };
            case 3 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                boolean doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    return paretoCompare(dominateFunctions[0], lhs.paretoValue1(), rhs.paretoValue1())
                            || paretoCompare(dominateFunctions[1], lhs.paretoValue2(), rhs.paretoValue2())
                            || paretoCompare(dominateFunctions[2], lhs.paretoValue3(), rhs.paretoValue3())
                            ;
                }
            };
            case 4 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                boolean doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    return paretoCompare(dominateFunctions[0], lhs.paretoValue1(), rhs.paretoValue1())
                            || paretoCompare(dominateFunctions[1], lhs.paretoValue2(), rhs.paretoValue2())
                            || paretoCompare(dominateFunctions[2], lhs.paretoValue3(), rhs.paretoValue3())
                            || paretoCompare(dominateFunctions[3], lhs.paretoValue4(), rhs.paretoValue4())
                            ;
                }
            };
            case 5 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                boolean doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    return paretoCompare(dominateFunctions[0], lhs.paretoValue1(), rhs.paretoValue1())
                            || paretoCompare(dominateFunctions[1], lhs.paretoValue2(), rhs.paretoValue2())
                            || paretoCompare(dominateFunctions[2], lhs.paretoValue3(), rhs.paretoValue3())
                            || paretoCompare(dominateFunctions[3], lhs.paretoValue4(), rhs.paretoValue4())
                            || paretoCompare(dominateFunctions[4], lhs.paretoValue5(), rhs.paretoValue5())
                            ;
                }
            };
            case 6 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                boolean doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    return paretoCompare(dominateFunctions[0], lhs.paretoValue1(), rhs.paretoValue1())
                            || paretoCompare(dominateFunctions[1], lhs.paretoValue2(), rhs.paretoValue2())
                            || paretoCompare(dominateFunctions[2], lhs.paretoValue3(), rhs.paretoValue3())
                            || paretoCompare(dominateFunctions[3], lhs.paretoValue4(), rhs.paretoValue4())
                            || paretoCompare(dominateFunctions[4], lhs.paretoValue5(), rhs.paretoValue5())
                            || paretoCompare(dominateFunctions[5], lhs.paretoValue6(), rhs.paretoValue6())
                            ;
                }
            };
            case 7 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                boolean doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    return paretoCompare(dominateFunctions[0], lhs.paretoValue1(), rhs.paretoValue1())
                            || paretoCompare(dominateFunctions[1], lhs.paretoValue2(), rhs.paretoValue2())
                            || paretoCompare(dominateFunctions[2], lhs.paretoValue3(), rhs.paretoValue3())
                            || paretoCompare(dominateFunctions[3], lhs.paretoValue4(), rhs.paretoValue4())
                            || paretoCompare(dominateFunctions[4], lhs.paretoValue5(), rhs.paretoValue5())
                            || paretoCompare(dominateFunctions[5], lhs.paretoValue6(), rhs.paretoValue6())
                            || paretoCompare(dominateFunctions[6], lhs.paretoValue7(), rhs.paretoValue7())
                            ;
                }
            };
            case 8 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                boolean doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    return paretoCompare(dominateFunctions[0], lhs.paretoValue1(), rhs.paretoValue1())
                            || paretoCompare(dominateFunctions[1], lhs.paretoValue2(), rhs.paretoValue2())
                            || paretoCompare(dominateFunctions[2], lhs.paretoValue3(), rhs.paretoValue3())
                            || paretoCompare(dominateFunctions[3], lhs.paretoValue4(), rhs.paretoValue4())
                            || paretoCompare(dominateFunctions[4], lhs.paretoValue5(), rhs.paretoValue5())
                            || paretoCompare(dominateFunctions[5], lhs.paretoValue6(), rhs.paretoValue6())
                            || paretoCompare(dominateFunctions[6], lhs.paretoValue7(), rhs.paretoValue7())
                            || paretoCompare(dominateFunctions[7], lhs.paretoValue8(), rhs.paretoValue8())
                            ;
                }
            };
            default:
                throw new IllegalStateException("ParetoSet with size " + dominateFunctions.length + " is not supported.");
        }
    }

    abstract boolean doDominate(ParetoSortable lhs, ParetoSortable rhs);

    Dominance dominate(ParetoSortable lhs, ParetoSortable rhs) {
        leftDominatesRight = false;
        rightDominatesLeft = false;
        return doDominate(lhs, rhs) ? Dominance.MutualDominance : paretoResult();
    }

    boolean paretoCompare(InternalParetoDominanceFunction func, int left, int right) {
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

    private Dominance paretoResult() {
        if (leftDominatesRight) return Dominance.LeftDominatesRight;
        if (rightDominatesLeft) return Dominance.RightDominatesLeft;
        return Dominance.MutualDominance;
    }
}