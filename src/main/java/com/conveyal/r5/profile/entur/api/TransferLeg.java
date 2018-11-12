package com.conveyal.r5.profile.entur.api;


/**
 * Encapsulate information about a transfer leg, or transfer stop arrival.
 */
public interface TransferLeg {

    /**
     * Stop index where the leg arrive at, the leg origin is part of the context and not this class.
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
