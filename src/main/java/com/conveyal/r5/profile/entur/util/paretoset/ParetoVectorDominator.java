package com.conveyal.r5.profile.entur.util.paretoset;


/**
 * This is an optimized class for calculating doninance between to pareto vectors.
 * <p/>
 * The class is highly optimized for performance. For example, by changing the code
 * to perform one single `if` less increased the RangeRaptor performance by many percent.
 * <p/>
 * THIS CLASS IS *NOT* THREAD SAFE.
 * <p/>
 */
abstract class ParetoVectorDominator {
    /**
     * Array callbacks to perform for each criteria (all values in the vector)
     */
    final ParetoFunction[] dominateFunctions;

    /**
     * Temporary storage for the calculation state - not thread safe.
     */
    private boolean newCriteriaDominatesExistingCriteria;

    /**
     * Temporary storage for the calculation state - not thread safe.
     */
    private boolean existingCriteriaDominatesNewCriteria;

    /**
     * Private constructor to force the use of {@link #create(ParetoFunction[])}.
     */
    private ParetoVectorDominator(ParetoFunction[] dominateFunctions) {
        this.dominateFunctions = dominateFunctions;
    }

    /**
     * Factory method to create a dominator using the exact vector size depending on the
     * <code>dominateFunctions</code> size.
     */
    static ParetoVectorDominator create(final ParetoFunction[] dominateFunctions) {
        switch (dominateFunctions.length) {
            case 1 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                void doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    dominateFunctions[0].dominate(lhs.paretoValue1(), rhs.paretoValue1(), this);
                }
            };
            case 2 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                void doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    dominateFunctions[0].dominate(lhs.paretoValue1(), rhs.paretoValue1(), this);
                    dominateFunctions[1].dominate(lhs.paretoValue2(), rhs.paretoValue2(), this);
                }
            };
            case 3 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                void doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    dominateFunctions[0].dominate(lhs.paretoValue1(), rhs.paretoValue1(), this);
                    dominateFunctions[1].dominate(lhs.paretoValue2(), rhs.paretoValue2(), this);
                    dominateFunctions[2].dominate(lhs.paretoValue3(), rhs.paretoValue3(), this);
                }
            };
            case 4 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                void doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    dominateFunctions[0].dominate(lhs.paretoValue1(), rhs.paretoValue1(), this);
                    dominateFunctions[1].dominate(lhs.paretoValue2(), rhs.paretoValue2(), this);
                    dominateFunctions[2].dominate(lhs.paretoValue3(), rhs.paretoValue3(), this);
                    dominateFunctions[3].dominate(lhs.paretoValue4(), rhs.paretoValue4(), this);
                }
            };
            case 5 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                void doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    dominateFunctions[0].dominate(lhs.paretoValue1(), rhs.paretoValue1(), this);
                    dominateFunctions[1].dominate(lhs.paretoValue2(), rhs.paretoValue2(), this);
                    dominateFunctions[2].dominate(lhs.paretoValue3(), rhs.paretoValue3(), this);
                    dominateFunctions[3].dominate(lhs.paretoValue4(), rhs.paretoValue4(), this);
                    dominateFunctions[4].dominate(lhs.paretoValue5(), rhs.paretoValue5(), this);
                }
            };
            case 6 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                void doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    dominateFunctions[0].dominate(lhs.paretoValue1(), rhs.paretoValue1(), this);
                    dominateFunctions[3].dominate(lhs.paretoValue4(), rhs.paretoValue4(), this);
                    dominateFunctions[1].dominate(lhs.paretoValue2(), rhs.paretoValue2(), this);
                    dominateFunctions[2].dominate(lhs.paretoValue3(), rhs.paretoValue3(), this);
                    dominateFunctions[4].dominate(lhs.paretoValue5(), rhs.paretoValue5(), this);
                    dominateFunctions[5].dominate(lhs.paretoValue6(), rhs.paretoValue6(), this);
                }
            };
            case 7 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                void doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    dominateFunctions[0].dominate(lhs.paretoValue1(), rhs.paretoValue1(), this);
                    dominateFunctions[1].dominate(lhs.paretoValue2(), rhs.paretoValue2(), this);
                    dominateFunctions[2].dominate(lhs.paretoValue3(), rhs.paretoValue3(), this);
                    dominateFunctions[3].dominate(lhs.paretoValue4(), rhs.paretoValue4(), this);
                    dominateFunctions[4].dominate(lhs.paretoValue5(), rhs.paretoValue5(), this);
                    dominateFunctions[5].dominate(lhs.paretoValue6(), rhs.paretoValue6(), this);
                    dominateFunctions[6].dominate(lhs.paretoValue7(), rhs.paretoValue7(), this);
                }
            };
            case 8 : return new ParetoVectorDominator(dominateFunctions) {
                @Override
                void doDominate(ParetoSortable lhs, ParetoSortable rhs) {
                    dominateFunctions[0].dominate(lhs.paretoValue1(), rhs.paretoValue1(), this);
                    dominateFunctions[1].dominate(lhs.paretoValue2(), rhs.paretoValue2(), this);
                    dominateFunctions[2].dominate(lhs.paretoValue3(), rhs.paretoValue3(), this);
                    dominateFunctions[3].dominate(lhs.paretoValue4(), rhs.paretoValue4(), this);
                    dominateFunctions[4].dominate(lhs.paretoValue5(), rhs.paretoValue5(), this);
                    dominateFunctions[5].dominate(lhs.paretoValue6(), rhs.paretoValue6(), this);
                    dominateFunctions[6].dominate(lhs.paretoValue7(), rhs.paretoValue7(), this);
                    dominateFunctions[7].dominate(lhs.paretoValue8(), rhs.paretoValue8(), this);
                }
            };
            default:
                throw new IllegalStateException("ParetoSet with size " + dominateFunctions.length + " is not supported.");
        }
    }

    final void applyDominance(boolean newDominatesExistingCriteria, boolean existingDominatesNewCriteria) {
        this.newCriteriaDominatesExistingCriteria |= newDominatesExistingCriteria;
        this.existingCriteriaDominatesNewCriteria |= existingDominatesNewCriteria;
    }

    abstract void doDominate(ParetoSortable lhs, ParetoSortable rhs);

    final void dominate(ParetoSortable lhs, ParetoSortable rhs) {
        newCriteriaDominatesExistingCriteria = false;
        existingCriteriaDominatesNewCriteria = false;
        doDominate(lhs, rhs);
    }

    final boolean mutualVectorDominantesExist() {
        return newCriteriaDominatesExistingCriteria && existingCriteriaDominatesNewCriteria;
    }

    final boolean newVectorDominatesExistingVector() {
        return newCriteriaDominatesExistingCriteria && !existingCriteriaDominatesNewCriteria;
    }

    final boolean newCriteriaDominatesExist() {
        return newCriteriaDominatesExistingCriteria;
    }

    final boolean existingCriteriaDominanceExist() {
        return existingCriteriaDominatesNewCriteria;
    }
}