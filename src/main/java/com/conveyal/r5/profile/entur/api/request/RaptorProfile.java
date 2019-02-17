package com.conveyal.r5.profile.entur.api.request;


/**
 * Several implementation are implemented - with different behaviour. Use the one
 * that suites your need best.
 */
public enum RaptorProfile {
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
     * Multi criteria pareto state with McRangeRaptor optimized by using a standard
     * reverse range raptor search to calculate heuristic destination data first.
     */
    MULTI_CRITERIA_RANGE_RAPTOR_WITH_HEURISTICS,

    /**
     * Perform a raptor search traversing the transit graph in reverse. Only one iteration is performed.
     * Search from destination to origin traversing the transit graph backwards in time.
     */
    RAPTOR_REVERSE
}
