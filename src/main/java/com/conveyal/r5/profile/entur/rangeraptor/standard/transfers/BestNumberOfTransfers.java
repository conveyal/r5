package com.conveyal.r5.profile.entur.rangeraptor.standard.transfers;

import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;
import com.conveyal.r5.profile.entur.util.IntUtils;


/**
 * The responsibility for this class is to keep track of the best (minimun)
 * number of transfers for all stops reached.
 */
public class BestNumberOfTransfers {

    private static final int UNREACHED = 1000;

    private final int[] bestNumOfTransfers;
    private final RoundProvider roundProvider;

    public BestNumberOfTransfers(int nStops, RoundProvider roundProvider) {
        this.bestNumOfTransfers = IntUtils.intArray(nStops, UNREACHED);
        this.roundProvider = roundProvider;
    }

    /**
     * Call this method to notify that the given stop is reached in the current round of Raptor.
     */
    public void visited(int stop) {
        final int numOfTransfers = roundProvider.round() - 1;
        if(numOfTransfers < bestNumOfTransfers[stop] ) {
            bestNumOfTransfers[stop] = numOfTransfers;
        }
    }

    /**
     * Return the minimum number for transfers used to reach the current stop.
     */
    public int minNumberOfTransfers(int stop) {
        return bestNumOfTransfers[stop];
    }
}
