package com.conveyal.r5.profile.entur.api.transit;


/**
 * Encapsulate information about the access leg or the first leg from the origin to the first transit stop.
 */
public interface AccessLeg {

    /**
     * The leg target stop index where the transit journey start.
     */
    int stop();

    /**
     * The time duration to walk or travel the leg in seconds.
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
