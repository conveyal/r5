package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.Path;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;


/**
 * Class used to represent transit paths in Browsochrones and Modeify.
 */
public class PathBuilderCursorBased implements PathBuilder {
    private final StopStateCursor cursor;
    private int round;


    private final TIntList patterns = new TIntArrayList();
    private final TIntList boardStops = new TIntArrayList();
    private final TIntList alightStops = new TIntArrayList();
//    private TIntList times = new TIntArrayList();
    private final TIntList alightTimes = new TIntArrayList();
    private final TIntList boardTimes = new TIntArrayList();
    private final TIntList transferTimes = new TIntArrayList();
    private final TIntList trips = new TIntArrayList();


    public PathBuilderCursorBased(StopStateCursor cursor) {
        this.cursor = cursor;
    }

    /**
     * Scan over a raptor state and extract the path leading up to that state.
     */
    public Path extractPathForStop(int maxRound, int egressStop) {
        this.round = maxRound;
        this.cursor.stop(round, egressStop);

        RangeRaptorWorkerStateImpl.debugStopHeader("FIND PATH");

        // find the fewest-transfers trip that is still optimal in terms of travel time
        StopState currentStop = findLastRoundWithTransitTimeSet(egressStop);

        if(currentStop == null) {
            return null;
        }

        // trace the path back from this RaptorState
        patterns.clear();
        boardStops.clear();
        alightStops.clear();
//        times.clear();
        alightTimes.clear();
        boardTimes.clear();
        transferTimes.clear();
        trips.clear();


        //state.debugStop("egress stop", state.round(), stop);

        int currentStopIndex = egressStop;

        while (round > 0) {
            patterns.add(currentStop.previousPattern());
            trips.add(currentStop.previousTrip());
            alightStops.add(currentStopIndex);
//            times.add(it.time());
            boardTimes.add(currentStop.boardTime());
            alightTimes.add(currentStop.transitTime());

            // TODO
            currentStopIndex = currentStop.boardStop();

            boardStops.add(currentStopIndex);

            // go to previous state before handling transfers as transfers are done at the end of a round
            --round;
            currentStop = cursor.stop(round, currentStopIndex);
            currentStop.debugStop("by", round, currentStopIndex);


            // handle transfers
            if (currentStop.arrivedByTransfer()) {
                transferTimes.add(currentStop.time());
                currentStopIndex = currentStop.transferFromStop();
                currentStop = cursor.stop(round, currentStopIndex);
                currentStop.debugStop("transfer", round, currentStopIndex);
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

    /**
     * This method search the stop from roundMax and back to round 1 to find
     * the last round with a transit time set. This is sufficient for finding the
     * best time, since the state is only recorded iff it is faster then previous rounds.
     */
    private StopState findLastRoundWithTransitTimeSet(int egressStop) {

        while(cursor.stopNotVisited(round, egressStop) || !cursor.stop(round, egressStop).isTransitTimeSet()) {

            //debugListedStops("skip no transit", round, stop);
            --round;
            if(round == -1) {
                return null;
            }
        }
        return cursor.stop(round, egressStop);
    }
}
