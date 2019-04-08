package com.conveyal.r5.otp2.rangeraptor.standard.besttimes;

import com.conveyal.r5.otp2.rangeraptor.standard.ArrivedAtDestinationCheck;

public class SimpleArrivedAtDestinationCheck implements ArrivedAtDestinationCheck {

    /**
     * The list of egress stops, can be used to terminate the search when the stops are reached.
     */
    private final int[] egressStops;
    private final BestTimes stopVisited;


    public SimpleArrivedAtDestinationCheck(int[] egressStops, BestTimes stopVisited) {
        this.egressStops = egressStops;
        this.stopVisited = stopVisited;
    }

    @Override
    public boolean arrivedAtDestinationCurrentRound() {
        // This is fast enough, we could use a BitSet for egressStops, but it takes up more
        // memory and the performance is the same.
        for (int i = 0; i < egressStops.length; i++) {
            if(stopVisited.isStopReachedByTransitCurrentRound(i)) {
                return true;
            }
        }
        return false;

    }
}
