package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.Path;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;


/**
 * Class used to represent transit paths in Browsochrones and Modeify.
 */
public class PathBuilder {
    private final StopStateFlyWeight.Cursor cursor;
    private int round;
    //private int stop;


    private final TIntList patterns = new TIntArrayList();
    private final TIntList boardStops = new TIntArrayList();
    private final TIntList alightStops = new TIntArrayList();
//    private TIntList times = new TIntArrayList();
    private final TIntList alightTimes = new TIntArrayList();
    private final TIntList boardTimes = new TIntArrayList();
    private final TIntList transferTimes = new TIntArrayList();
    private final TIntList trips = new TIntArrayList();


    public PathBuilder(StopStateFlyWeight.Cursor cursor) {
        this.cursor = cursor;
    }

    /**
     * Scan over a raptor state and extract the path leading up to that state.
     */
    Path extractPathForStop(int maxRound, int egressStop) {
        this.round = maxRound;
        this.cursor.stop(round, egressStop);

        // trace the path back from this RaptorState
        patterns.clear();
        boardStops.clear();
        alightStops.clear();
//        times.clear();
        alightTimes.clear();
        boardTimes.clear();
        transferTimes.clear();
        trips.clear();

        McRaptorStateImpl.debugStopHeader("FIND PATH");

        // find the fewest-transfers trip that is still optimal in terms of travel time
        boolean found = findLastRoundWithTransitTimeSet(egressStop);

        if(!found) {
            throw new IllegalStateException("Transit for stop not found. Stop: " + egressStop + ".");
        }

        //state.debugStop("egress stop", state.round(), stop);

        int currentStop = egressStop;

        while (round > 0) {
            patterns.add(cursor.previousPattern());
            trips.add(cursor.previousTrip());
            alightStops.add(currentStop);
//            times.add(it.time());
            boardTimes.add(cursor.boardTime());
            alightTimes.add(cursor.transitTime());

            // TODO
            currentStop = cursor.boardStop();

            boardStops.add(currentStop);

            // go to previous state before handling transfers as transfers are done at the end of a round
            --round;
            cursor.stop(round, currentStop);
            cursor.debugStop("by", round, currentStop);


            // handle transfers
            if (cursor.arrivedByTransfer()) {
                transferTimes.add(cursor.time());
                currentStop = cursor.transferFromStop();
                cursor.stop(round, currentStop);
                cursor.debugStop("transfer", round, currentStop);
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
    private boolean findLastRoundWithTransitTimeSet(int egressStop) {

        while (round > 0 && !cursor.isTransitTimeSet()) {

            //debugListedStops("skip no transit", round, stop);
            --round;
            cursor.stop(round, egressStop);
        }
        return round > 0;
    }
}
