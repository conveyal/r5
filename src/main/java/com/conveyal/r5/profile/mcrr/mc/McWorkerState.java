package com.conveyal.r5.profile.mcrr.mc;

import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.mcrr.BitSetIterator;
import com.conveyal.r5.profile.mcrr.api.Path2;
import com.conveyal.r5.profile.mcrr.api.TimeToStop;
import com.conveyal.r5.profile.mcrr.util.DebugState;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

import static com.conveyal.r5.profile.mcrr.util.DebugState.Type.Access;
import static com.conveyal.r5.profile.mcrr.util.DebugState.Type.Transfer;
import static com.conveyal.r5.profile.mcrr.util.DebugState.Type.Transit;


/**
 * Tracks the state of a RAPTOR search, specifically the best arrival times at each transit stop at the end of a
 * particular round, along with associated data to reconstruct paths etc.
 * <p>
 * This is grouped into a separate class (rather than just having the fields in the raptor worker class) because we
 * need to make copies of it when doing Monte Carlo frequency searches. While performing the range-raptor search,
 * we keep performing raptor searches at different departure times, stepping back in time, but operating on the same
 * set of states (one for each round). But after each one of those departure time searches, we want to run sub-searches
 * with different randomly selected schedules (the Monte Carlo draws). We don't want those sub-searches to invalidate
 * the states for the ongoing range-raptor search, so we make a protective copy.
 * <p>
 * Note that this represents the entire state of the RAPTOR search for a single round, rather than the state at
 * a particular vertex (transit stop), as is the case with State objects in other search algorithms we have.
 *
 * @author mattwigway
 */
public final class McWorkerState {

    /** Maximum duration of trips stored by this RaptorState */
    private final int maxDurationSeconds;

    /** Stop the search when the time exceeds the max time limit. */
    private int maxTimeLimit;


    private final StopStatesParetoSet stops;
    private final int nRounds;
    private int round = Integer.MIN_VALUE;

    private BitSet touchedCurrent;
    private BitSet touchedPrevious;


    /** create a RaptorState for a network with a particular number of stops, and a given maximum duration */
    public McWorkerState(int nRounds, int nStops, int maxDurationSeconds) {
        this.nRounds = nRounds;
        this.stops = new StopStatesParetoSet(nStops);

        this.touchedCurrent = new BitSet(nStops);
        this.touchedPrevious = new BitSet(nStops);

        this.maxDurationSeconds = maxDurationSeconds;
    }

    void initNewDepatureForMinute(int departureTime) {
        //this.departureTime = departureTime;
        maxTimeLimit = departureTime + maxDurationSeconds;
        // clear all touched stops to avoid constant rexploration
        touchedCurrent.clear();
        touchedPrevious.clear();
        round = 0;
    }

    void setInitialTime(int stop, int fromTime,  int accessTime) {
        stops.setInitialTime(stop, fromTime, accessTime);
        touchedCurrent.set(stop);
        debugStops(Access, round, stop);
    }

    boolean isNewRoundAvailable() {
        final boolean moreRoundsToGo = round < nRounds-1;
        return moreRoundsToGo && isCurrentRoundUpdated();
    }

    void gotoNextRound () {
        ++round;
    }

    BitSetIterator stopsTouchedPreviousRound() {
        mergeAndSwapTouchedStops();
        return new BitSetIterator(touchedPrevious);
    }

    BitSetIterator stopsTouchedByTransitCurrentRound() {
        swapTouchedStops();
        return new BitSetIterator(touchedPrevious);
    }

    Iterable<? extends McStopState> listStopStatesPreviousRound(int stop) {
        return stops.list(round-1, stop);
    }


    /**
     * Set the time at a transit stop iff it is optimal.
     */
    void transitToStop(McStopState boardStop, int stop, int alightTime, int pattern, int trip, int boardTime) {
        if (alightTime > maxTimeLimit) {
            return;
        }

        boolean added = stops.transitToStop(boardStop, round, stop, alightTime, pattern, trip, boardTime);

        if (added) {
            touchedCurrent.set(stop);
            // skip: transferTimes
            debugStops(Transit, round, stop);
        }
    }

    /**
     * Set the time at a transit stop iff it is optimal.
     */
    void transferToStop(int fromStop, int targetStop, int transferTimeInSeconds) {
        for(McStopState it :  stops.listArrivedByTransit(round, fromStop)) {
            int arrivalTime = it.time() + transferTimeInSeconds;

            if (arrivalTime < maxTimeLimit) {
                if(stops.transferToStop(it, round, targetStop, arrivalTime, transferTimeInSeconds)) {
                    touchedCurrent.set(targetStop);
                }
            }
        }
        debugStops(Transfer, round, targetStop);
    }

    Collection<Path2> extractPaths(Collection<TimeToStop> egressStops) {
        List<Path2> paths = new ArrayList<>();
        McPath2Builder builder = new McPath2Builder();

        for (TimeToStop egressStop : egressStops) {
            for (McStopState it : stops.listAll(egressStop.stop)) {
                Path2 p = builder.extractPathsForStop(it, egressStop.time);
                if(p != null) {
                    paths.add(p);
                }
            }
        }
        return paths;
    }

    static void debugStopHeader(String title) {
        DebugState.debugStopHeader(title,"C P");
    }


    /* private methods */

    private boolean isCurrentRoundUpdated() {
        return !touchedCurrent.isEmpty();
    }

    private void mergeAndSwapTouchedStops() {
        touchedCurrent.or(touchedPrevious);
        swapTouchedStops();
    }

    private void swapTouchedStops() {
        BitSet temp = touchedPrevious;
        touchedPrevious = touchedCurrent;
        touchedCurrent = temp;
        touchedCurrent.clear();
    }

    private void debugStops(DebugState.Type type, int round, int stop) {
        if (DebugState.isDebug(stop)) {
            String postfix = (touchedCurrent.get(stop) ? "x " : "  ") + (touchedPrevious.get(stop) ? "x" : " ");
            for (McStopState it : stops.list(round, stop)) {
                if(it.type() == type) {
                    DebugState.debugStop(type, round, stop, it, postfix);
                }
            }
        }
    }
}
