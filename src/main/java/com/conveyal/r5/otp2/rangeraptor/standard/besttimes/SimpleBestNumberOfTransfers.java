package com.conveyal.r5.otp2.rangeraptor.standard.besttimes;

import com.conveyal.r5.otp2.rangeraptor.RoundProvider;
import com.conveyal.r5.otp2.rangeraptor.standard.BestNumberOfTransfers;
import com.conveyal.r5.otp2.util.IntUtils;


/**
 * The responsibility for this class is to keep track of the best (minimun)
 * number of transfers for all stops reached.
 */
public class SimpleBestNumberOfTransfers implements BestNumberOfTransfers {
    private final int[] bestNumOfTransfers;
    private final RoundProvider roundProvider;

    public SimpleBestNumberOfTransfers(int nStops, RoundProvider roundProvider) {
        this.bestNumOfTransfers = IntUtils.intArray(nStops, unreachedMinNumberOfTransfers());
        this.roundProvider = roundProvider;
    }

    /**
     * Call this method to notify that the given stop is reached in the current round of Raptor.
     */
    void arriveAtStop(int stop) {
        final int numOfTransfers = roundProvider.round() - 1;
        if(numOfTransfers < bestNumOfTransfers[stop] ) {
            bestNumOfTransfers[stop] = numOfTransfers;
        }
    }

    @Override
    public int calculateMinNumberOfTransfers(int stop) {
        return bestNumOfTransfers[stop];
    }
}
