package com.conveyal.r5.profile.entur.api;


/**
 * Several implementation are implemented - with different behaviour. Use the one
 * that suites your need best.
 */
public enum RaptorProfiles {
    /** Range Raptor finding the earliest arrival time, shortest travel duration and fewest transfers. */
    RANGE_RAPTOR,
    /** Multi criteria pareto state with McRangeRaptor */
    MULTI_CRITERIA_RANGE_RAPTOR
    ;


    public boolean isMultiCriteria() {
        return is(MULTI_CRITERIA_RANGE_RAPTOR);
    }

    public boolean isPlainRangeRaptor() {
        return is(RANGE_RAPTOR);
    }

    private boolean is(RaptorProfiles other) {
        return this == other;
    }

}
