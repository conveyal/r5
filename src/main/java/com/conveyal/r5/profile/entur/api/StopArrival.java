package com.conveyal.r5.profile.entur.api;


/**
 * Encapsulate information about a stop arrival (or leg), including the stop index and time. What the information
 * represent depend on the context.
 * <p/>
 * The class is used to describe:
 * <ol>
 *     <li> assess legs (the arrival at the first stop in a trip)
 *     <li> transfer legs (arrival at a stop from a transfer)
 *     <li> egress legs
 * </ol>
 * Note that the {@link #stop()} refer to the destination stop in the 2 first cases, and the departure stop for egress legs.
 *
 *
 */
public interface StopArrival {

    /**
     * For an <em>assess legs</em> or <em>transfer legs</em> this is the arrival stop.
     * <p/>
     * For an <em>egress legs</em> this is the legs departure stop.
     */
    int stop();

    /**
     * The time duration to walk or travel the leg in seconds. This is not the entire duration from the journey origin.
     */
    int durationInSeconds();

    /**
     * The additional cost to be added to the pareto vector.
     * Optional - this method is only needed if the the cost is part of the pareto set.
     */
    default int cost() {
        return 0;
    }
}
