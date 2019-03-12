package com.conveyal.r5.profile.entur.rangeraptor.view;


import java.util.BitSet;

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
     * To plot or debug the travel duration.
     * @param unreached set all unreached values to this value
     */
    int[] bestTravelDurationToIntArray(int unreached);

    /**
     * The best number of transfers to reach the given stop.
     */
    int bestNumOfTransfers(int stop);

    /**
     * To plot or debug the number of transfers.
     * @param unreached set all unreached values to this value
     */
    int[] bestNumOfTransfersToIntArray(int unreached);

    /**
     * The number of stops in the heuristics. This include all stops also stops not reached.
     */
    int size();

    /**
     * Return the best/minimum required time to travel from origin to destination.
     */
    int bestOverallJourneyTravelDuration();

    /**
     * Return the best/minimum required number of transfers from origin to destination.
     */
    int bestOverallJourneyNumOfTransfers();

    /**
     * Combine two Heuristics to produce a stop filter. The heuristics should be computed with a
     * forward and a reverse search. For a given stop the two {@link #bestNumOfTransfers(int)}
     * are added and if the sum is better than the given maxNumberOfTransferLimit, the flag in
     * the returned bit set is enabled.
     */
    default BitSet stopFilter(Heuristics h2, final int maxNumberOfTransferLimit) {
        int n = size();
        BitSet stopFilter = new BitSet(n);
        for (int i=0; i<n; ++i) {
            int totNTransfers = bestNumOfTransfers(i) + h2.bestNumOfTransfers(i);
            if (totNTransfers < maxNumberOfTransferLimit) {
                stopFilter.set(i, true);
            }
        }
        return stopFilter;
    }
}
