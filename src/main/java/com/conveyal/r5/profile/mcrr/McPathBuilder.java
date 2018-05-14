package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.Path;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Class used to represent transit paths in Browsochrones and Modeify.
 */
public class McPathBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(McPathBuilder.class);

    /**
     * Scan over a raptor state and extract the path leading up to that state.
     */
    public static Path extractPathForStop(McRaptorState state, int stop) {
        // trace the path back from this RaptorState
        int previousPattern;
        int previousTrip;
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList times = new TIntArrayList();
        TIntList alightTimes = new TIntArrayList();
        TIntList boardTimes = new TIntArrayList();
        TIntList transferTimes = new TIntArrayList();
        TIntList trips = new TIntArrayList();

        while (state.previous != null) {
            // find the fewest-transfers trip that is still optimal in terms of travel time
            if (state.previous.bestNonTransferTimes[stop] == state.bestNonTransferTimes[stop]) {
                state = state.previous;
                continue;
            }

            if (state.previous.bestNonTransferTimes[stop] < state.bestNonTransferTimes[stop]) {
                LOG.error("Previous round has lower weight at stop {}, this implies a bug!", stop);
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
