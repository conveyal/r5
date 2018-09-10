package com.conveyal.r5.profile.mcrr;


import com.conveyal.r5.profile.mcrr.util.DebugState;

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
public final class RangeRaptorWorkerState {

    /**
     * To debug a particular journey set DEBUG to true and add all visited stops in the debugStops list.
     */
    private final StopStateCollection stops;
    private final StopStateCursor cursor;
    private final int nRounds;
    private int round = 0;
    private int roundMax = -1;


    /**
     * Earliest possible departure time for the search.
     * RangeRaptor iterate over departure times, but this is the first one.
     */
    private final int earliestDepartureTime;

    /** Maximum duration of trips stored by this RaptorState */
    private final int maxDurationSeconds;

    /** Stop the search when the time excids the max time limit. */
    private int maxTimeLimit;


    /** The best times to reach each stop, whether via a transfer or via transit directly. */
    private final BestTimes bestOveral;

    /** Index to the best times for reaching stops via transit rather than via a transfer from another stop */
    private final BestTimes bestTransit;


    /** create a RaptorState for a network with a particular number of stops, and a given maximum duration */
    public RangeRaptorWorkerState(int nRounds, int nStops, int earliestDepartureTime, int maxDurationSeconds, StopStateCollection stops) {
        this.nRounds = nRounds;
        this.stops = stops;
        this.cursor = stops.newCursor();

        this.bestOveral = new BestTimes(nStops);
        this.bestTransit = new BestTimes(nStops);

        this.maxDurationSeconds = maxDurationSeconds;
        this.earliestDepartureTime = earliestDepartureTime;
    }

    void gotoNextRound () {
        bestOveral.gotoNextRound();
        bestTransit.gotoNextRound();
        ++round;
        roundMax = Math.max(roundMax, round);
    }
    boolean isNewRoundAvailable() {
        final boolean moreRoundsToGo = round < nRounds-1;
        return moreRoundsToGo && isCurrentRoundUpdated();
    }

    boolean isStopReachedInPreviousRound(int stop) {
        return bestOveral.isReachedLastRound(stop);
    }

    BitSetIterator bestStopsTouchedLastRoundIterator() {
        return bestOveral.stopsReachedLastRound();
    }

    int getMaxNumberOfRounds() {
        return roundMax;
    }

    boolean isStopReachedByTransit(int stop) {
        return bestTransit.isReached(stop);
    }

    BitSetIterator stopsTouchedByTransitCurrentRoundIterator() {
        return bestTransit.stopsReachedCurrentRound();
    }

    int bestTimePreviousRound(int stop) {
        return cursor.stop(round-1, stop).time();
    }

    void initNewDepatureForMinute(int departureTime) {
        //this.departureTime = departureTime;
        maxTimeLimit = departureTime + maxDurationSeconds;
        // clear all touched stops to avoid constant reëxploration
        bestOveral.clearCurrent();
        bestTransit.clearCurrent();
        round = 0;
    }

    void setInitialTime(int stop, int time) {
        stops.setInitalTime(round, stop, time);
        bestOveral.setTime(stop, time);
        debugStop(Access, round, stop);
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the transitTime
     */
    void transitToStop(int stop, int alightTime, int pattern, int trip, int boardStop, int boardTime) {
        if (alightTime > maxTimeLimit) {
            return;
        }

        if (bestTransit.updateNewBestTime(stop, alightTime)) {

            // transitTimes upper bounds bestTimes
            final boolean newBestOveral = bestOveral.updateNewBestTime(stop, alightTime);

            stops.transitToStop(round, stop, alightTime, pattern, boardStop, trip, boardTime, newBestOveral);

            // skip: transferTimes
            debugStop(Transit, round, stop);
        }
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the nonTransferTime
     */
    void transferToStop(int fromStop, int toStop, int transferTimeInSeconds) {

        int time = bestTransit.time(fromStop) + transferTimeInSeconds;

        if (time > maxTimeLimit) {
            return;
        }
        // transitTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        if (bestOveral.updateNewBestTime(toStop, time)) {

            stops.transferToStop(round, toStop, time, fromStop, transferTimeInSeconds);

            debugStop(Transfer, round, toStop);
        }
    }

    static void debugStopHeader(String title) {
        DebugState.debugStopHeader(title, "Best     C P | Transit  C P");
    }


    /* private methods */

    private boolean isCurrentRoundUpdated() {
        return !(bestOveral.isCurrentRoundEmpty() && bestTransit.isCurrentRoundEmpty());
    }

    private void debugStop(DebugState.Type type, int round, int stop) {
        if(DebugState.isDebug(stop)) {
            DebugState.debugStop(type, round, stop, cursor.stop(round, stop), bestOveral.toString(stop) + " | " + bestTransit.toString(stop));
        }
    }
}
