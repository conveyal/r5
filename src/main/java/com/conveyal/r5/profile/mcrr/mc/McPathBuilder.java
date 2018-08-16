package com.conveyal.r5.profile.mcrr.mc;

import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.mcrr.util.DebugState;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import java.util.Arrays;
import java.util.List;


/**
 * TODO TGR
 */
class McPathBuilder {
    private final TIntList boardStops = new TIntArrayList();
    private final TIntList boardTimes = new TIntArrayList();
    private final TIntList alightStops = new TIntArrayList();
    private final TIntList alightTimes = new TIntArrayList();
    private final TIntList patterns = new TIntArrayList();
    private final TIntList trips = new TIntArrayList();
    private final TIntList transferTimes = new TIntArrayList();
    private final List<TIntList> all = Arrays.asList(
            boardStops,
            boardTimes,
            alightStops,
            alightTimes,
            patterns,
            trips,
            transferTimes
    );


    Path extractPathsForStop(McStopState egressStop) {

        if(!egressStop.arrivedByTransit()) {
            return null;
        }
        debugPath(egressStop);

        // trace the path back from this RaptorState
        all.forEach(TIntList::clear);

        McStopState it = egressStop;

        while (it.previousState() != null) {
            boardStops.add(it.boardStop());
            boardTimes.add(it.boardTime());
            alightStops.add(it.stopIndex());
            alightTimes.add(it.time());
            patterns.add(it.pattern());
            trips.add(it.trip());
            //times.add(it.time());

            it = it.previousState();

            if(it.arrivedByTransfer()) {
                // Note! The path 'transferTime' is the arrival time not the time it takes to transfer
                transferTimes.add(it.time());
                it = it.previousState();
            }
            else {
                transferTimes.add(-1);
            }
        }

        // we traversed up the tree but the user wants to see paths down the tree
        // TODO when we do reverse searches we won't want to reverse paths
        all.forEach(TIntList::reverse);

        int size = patterns.size();

        all.forEach(a -> {if(a.size() != size) throw new IllegalStateException("Path not balansed: " + this);});

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

    private void debugPath(McStopState egressStop) {
        DebugState.debugStopHeader("MC - CREATE PATH FOR EGRESS STOP: " + egressStop.stopIndex());

        if(DebugState.isDebug(egressStop.stopIndex())) {
            for (McStopState p : egressStop.path()) {
                p.debug();
            }
        }
    }
}
