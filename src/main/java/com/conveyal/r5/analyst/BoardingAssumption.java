package com.conveyal.r5.analyst;

/**
 * The assumptions made when boarding a frequency vehicle: best case (no wait), worst case (full headway)
 * and Monte Carlo (which produces the average).
 *
 * We frequently refer to these as "minimum, maximum, and average" but they are not exactly the minimum and maximum
 * because it's very unlikely that you could realize the best or worst case travel time to every location at once
 * So in reality they are just lower and upper bounds on accessibility.
 *
 * This means that the min and max accessibility for frequency networks actually have a different meaning than the
 * min and max for scheduled networks. In a scheduled network the min and max represent the minimum and maximum number
 * of jobs that could be simultaneously reached in less than a given travel time cutoff at any single departure
 * minute in the time window. In a frequency network the best case or maximum represents the sum of all jobs that could
 * be reached at any departure minute, regardless of whether they can be reached within the same Monte Carlo draw (i.e.
 * a particular timetabled realization of the service which was specified as frequencies). Similarly, the worst case
 * or minimum represents all the jobs that can always be reached independent of departure minute, unlike the scheduled
 * search where the minimum may include some jobs that can't be reached at every minute.
 *
 * Therefore on a scheduled network the minimum may be higher and the maximum may be lower than what we'd see with an
 * equivalent frequency network independent of timed transfers. However, the indicators yielded by frequency networks
 * are bounds on the indicators yielded by scheduled networks. This is true even in a mixed schedule and frequency
 * network.
 */
public enum BoardingAssumption {

    /** Be optimistic: assume that frequency-based services are always coming when you get to a stop. */
    BEST_CASE,

    /** Be pessimistic: assume that frequency-based services have always just left when you get to a stop. */
    WORST_CASE,

    /** Use a Monte Carlo simulation where a bunch of different random schedules are generated. */
    RANDOM;

    public static final long serialVersionUID = 1;
}