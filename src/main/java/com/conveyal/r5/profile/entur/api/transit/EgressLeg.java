package com.conveyal.r5.profile.entur.api.transit;


/**
 * Encapsulate information about an egress leg. In other words the leg from the egress stop to the final destination.
 */
public interface EgressLeg {

    /**
     * Stop index to depart from.
     */
    int stop();

    /**
     * The time duration to walk or travel the leg in seconds.
     * This is <em>not</em> the entire duration from the journey origin.
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
