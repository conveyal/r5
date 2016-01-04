package com.conveyal.r5.analyst;

/**
 * The assumptions made when boarding a frequency vehicle: best case (no wait), worst case (full headway)
 * and half headway (in some sense the average).
 */
public enum BoardingAssumption {

    /** be optimistic: assume that frequency-based services are always coming when you get to a stop */
    BEST_CASE,

    /** be pessimistic: assume that frequency-based services have always just left when you get to a stop */
    WORST_CASE,

    /** in some sense the average: assume that you always wait half of the time between vehicles */
    HALF_HEADWAY,

    FIXED,

    PROPORTION,

    /** Use a Monte Carlo simulation where a bunch of different random schedules are generated. */
    RANDOM;

    public static final long serialVersionUID = 1;
}