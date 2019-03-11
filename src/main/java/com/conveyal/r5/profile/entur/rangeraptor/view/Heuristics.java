package com.conveyal.r5.profile.entur.rangeraptor.view;


/**
 * The heuristics is used in the multi-criteria search and can be generated using the standard
 * search. This interface decople these two implementations and make it possible to implement
 * more than one heuristics data provider.
 */
public interface Heuristics {
    /**
     * Is the stop reached by the heuristic search?
     */
    boolean reached(int stop);

    /**
     * The best overall travel duration from origin to the given stop.
     */
    int bestTravelDuration(int stop);

    /**
     * The best number of transfers to reach the given stop.
     */
    int bestNumOfTransfers(int stop);

    /**
     * The number of stops in the heuristics. This include all stops also stops not reached.
     */
    int size();
}
