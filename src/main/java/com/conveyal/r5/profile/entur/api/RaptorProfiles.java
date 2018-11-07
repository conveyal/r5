package com.conveyal.r5.profile.entur.api;


/**
 * Several implementation are implemented - with different behaviour. Use the one
 * that suites your need best.
 */
public enum RaptorProfiles {
    /** Flyweight stop state using int arrays with Range Raptor */
    INT_ARRAYS,
    /** Simple POJO stop arrival state with Range Raptor */
    STRUCT_ARRAYS,
    /** Multi criteria pareto state with McRangeRaptor */
    MULTI_CRITERIA
    ;


    public boolean isMultiCriteria() {
        return is(MULTI_CRITERIA);
    }

    public boolean isPlainRangeRaptor() {
        return is(STRUCT_ARRAYS) || is(INT_ARRAYS);
    }

    private boolean is(RaptorProfiles other) {
        return this == other;
    }

}
