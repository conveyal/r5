package com.conveyal.r5.profile.entur.api;


/**
 * Encapsulate information about a stop arrival, including the stop index and time to get there.
 * The information about the denature place (stop or location) is given by the context and not part of the class.
 */
public interface StopArrival {

    /** The destination stop */
    int stop();

    /** The time duration to reach the stop in seconds. This is just the time from the
     * previous place to this stop, not the entire duration from the journey origin. */
    int durationInSeconds();

    /**
     * The additional cost to be added to the pareto vector.
     * Optional - this method is only needed if the the cost is part of the pareto set.
     */
    default int cost() {
        return 0;
    };
}
