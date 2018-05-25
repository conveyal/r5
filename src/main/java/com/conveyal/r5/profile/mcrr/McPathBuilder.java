package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.Path;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;


/**
 * Class used to represent transit paths in Browsochrones and Modeify.
 */
public class McPathBuilder {
    private TIntList patterns = new TIntArrayList();
    private TIntList boardStops = new TIntArrayList();
    private TIntList alightStops = new TIntArrayList();
    private TIntList times = new TIntArrayList();
    private TIntList alightTimes = new TIntArrayList();
    private TIntList boardTimes = new TIntArrayList();
    private TIntList transferTimes = new TIntArrayList();
    private TIntList trips = new TIntArrayList();

    /**
     * Scan over a raptor state and extract the path leading up to that state.
     */
    public  Path extractPathForStop(McRaptorState state, int stop) {
        // trace the path back from this RaptorState
        int previousPattern;
        int previousTrip;
        patterns.clear();
        boardStops.clear();
        alightStops.clear();
        times.clear();
        alightTimes.clear();
        boardTimes.clear();
        transferTimes.clear();
        trips.clear();

        while (state.previous != null) {
            // find the fewest-transfers trip that is still optimal in terms of travel time
            if (state.previous.bestNonTransferTimes[stop] == state.bestNonTransferTimes[stop]) {
                state = state.previous;
                continue;
            }

            if (state.previous.bestNonTransferTimes[stop] < state.bestNonTransferTimes[stop]) {
                throw new IllegalStateException("Previous round has lower weight at stop " + stop + ", this implies a bug!");
            }

            previousPattern = state.previousPatterns[stop];
            previousTrip = state.previousTrips[stop];

            patterns.add(previousPattern);
            trips.add(previousTrip);
            alightStops.add(stop);
            times.add(state.bestTimes[stop]);
            alightTimes.add(state.bestNonTransferTimes[stop]);
            boardTimes.add(state.boardTimes[stop]);
            stop = state.previousStop[stop];
            boardStops.add(stop);

            // go to previous state before handling transfers as transfers are done at the end of a round
            state = state.previous;

            // handle transfers
            if (state.transferStop[stop] != -1) {
                transferTimes.add(state.transferTimes[stop]);
                stop = state.transferStop[stop];
            }
            else {
                transferTimes.add(-1);
            }
        }

        // we traversed up the tree but the user wants to see paths down the tree
        // TODO when we do reverse searches we won't want to reverse paths
        patterns.reverse();
        boardStops.reverse();
        alightStops.reverse();
        alightTimes.reverse();
        boardTimes.reverse();
        trips.reverse();
        transferTimes.reverse();

        return new Path(
                patterns.toArray(),
                boardStops.toArray(),
                alightStops.toArray(),
                alightTimes.toArray(),
                trips.toArray(),
                null,
                null,
                boardTimes.toArray(),
                transferTimes.toArray()
        );
    }
}
