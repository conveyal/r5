package com.conveyal.r5.profile.entur.api.request;


/**
 * Several implementation are implemented - with different behaviour. Use the one
 * that suites your need best.
 */
public enum RaptorProfiles {
    /**
     * Range Raptor finding the earliest arrival time, shortest travel duration and fewest transfers.
     * The cost is not used.
     */
    RANGE_RAPTOR,

    /**
     * Multi criteria pareto state with McRangeRaptor.
     */
    MULTI_CRITERIA_RANGE_RAPTOR,

    /**
     * Perform a raptor search traversing the transit graph in reverse. Only one iteration is performed.
     * Search from destination to origin traversing the transit graph backwards in time.
     */
    RAPTOR_REVERSE
}
