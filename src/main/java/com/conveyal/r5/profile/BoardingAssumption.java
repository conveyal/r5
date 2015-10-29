package com.conveyal.r5.profile;

/**
 * Assumption used to board a route.
 */
public enum BoardingAssumption {
    /** be optimistic: assume that frequency-based services are always coming when you get to a stop */
    BEST_CASE,

    /** be pessimistic: assume that frequency-based services have always just left when you get to a stop */
    WORST_CASE,

    /** be realistic, do a monte carlo simulation of possible schedules */
    RANDOM
}
