package com.conveyal.r5.profile.mcrr;

import java.util.Arrays;
import java.util.List;

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
public final class McRaptorState {

    /**
     * To debug a particular journey set DEBUG to true and add all visited stops in the debugStops list.
     */
    private static final boolean DEBUG = false;
    private static final List<Integer> debugStops = Arrays.asList(5757, 32489, 17270, 21469, 22102);

    /**
     * This value essentially serves as Infinity for ints - it's bigger than every other number.
     * It is the travel time to a transit stop or a target before that stop or target is ever reached.
     * Be careful when propagating travel times from stops to targets, adding something to UNREACHED will cause overflow.
     */
    public static final int UNREACHED = Integer.MAX_VALUE;

    static final int NOT_SET = -1;

    private final StopStateFlyWeight state;
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


    private final int[][] stateStopIndex;

    /** The best times to reach each stop, whether via a transfer or via transit directly. */
    private final BestTimes bestOveral;

    /** Index to the best times for reaching stops via transit rather than via a transfer from another stop */
    private final BestTimes bestTransit;


    /** create a RaptorState for a network with a particular number of stops, and a given maximum duration */
    McRaptorState(int nStops, int nRounds, int maxDurationSeconds, int earliestDepartureTime) {
        this.nRounds = nRounds;
        this.state = new StopStateFlyWeight(nStops * 3);

        this.stateStopIndex = new int[nRounds][nStops];

        this.bestOveral = new BestTimes(nStops);
        this.bestTransit = new BestTimes(nStops);

        this.maxDurationSeconds = maxDurationSeconds;
        this.earliestDepartureTime = earliestDepartureTime;
    }

    void gotoNextRound() {
        bestOveral.gotoNextRound();
        bestTransit.gotoNextRound();
        ++round;
        roundMax = Math.max(roundMax, round);
    }

    void gotoPreviousRound() {
        --round;
    }

    int round() {
        return round;
    }

    boolean isNewRoundAvailable() {
        final boolean moreRoundsToGo = round < nRounds-1;
        return moreRoundsToGo && isCurrentRoundUpdated();
    }

    /**
     * This method search the stop from roundMax and back to round 1 to find
     * the last round with a transit time set. This is sufficient for finding the
     * best time, since the state is only recorded iff it is faster then previous rounds.
     */
    void findLastRoundWithTransitTimeSet(int stop) {
        round = roundMax;

        while (round > 0 && !stop(stop).isTransitTimeSet()) {
            debugListedStops("skip no transit", round, stop);
            --round;
        }
    }

    boolean isStopReachedByTransit(int stop) {
        return this.bestTransit.isReached(stop);
    }

    boolean isStopReachedInLastRound(int stop) {
        return bestOveral.isReachedLastRound(stop);
    }

    BitSetIterator bestStopsTouchedLastRoundIterator() {
        return bestOveral.stopsReachedLastRound();
    }

    BitSetIterator stopsTouchedByTransitCurrentRoundIterator() {
        return bestTransit.stopsReachedCurrentRound();
    }

    int bestTimePreviousRound(int stop) {
        // TODO TGR
        //return state.time(stateStopIndex[round-1][stop]);
        return bestOveral.timeLastRound(stop);
    }

    int bestTransitTime(int stop) {
        return bestTransit.time(stop);
    }

    StopState stop(int stop) {
        state.setCursor(stopIndex(stop));
        return state;
    }

    StopState stopPreviousRound(int stop) {
        state.setCursor(stateStopIndex[round-1][stop]);
        return state;
    }

    void initNewDepatureForMinute(int roundDepartureTime) {
        //this.departureTime = departureTime;
        this.maxTimeLimit = roundDepartureTime + maxDurationSeconds;
        // clear all touched stops to avoid constant reëxploration
        bestOveral.clearCurrent();
        bestTransit.clearCurrent();
        round = 0;
    }

    void setInitialTime(int stop, int time) {
        final int stateIndex = findOrCreateStopIndex(stop);
        state.setInitalTime(stateIndex, time);
        bestOveral.setTime(stop, time);
        debugListedStops("init", round, stop);
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the transitTime
     */
    void transitToStop(int stop, int time, int fromPattern, int boardStop, int tripIndex, int boardTime) {
        if (time > maxTimeLimit) {
            return;
        }

        if (bestTransit.updateNewBestTime(stop, time)) {
            if(stop==18914) {
                debugListedStops("transit to stop", round, stop);
            }

            final int stateIndex = findOrCreateStopIndex(stop);

            // transitTimes upper bounds bestTimes
            final boolean newBestOveral = bestOveral.updateNewBestTime(stop, time);

            state.transitToStop(stateIndex, time, fromPattern, boardStop, tripIndex, boardTime, newBestOveral);

            // skip: transferTimes
            debugListedStops("transit to stop", round, stop);
        }
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the nonTransferTime
     */
    void transferToStop(int stop, int time, int fromStop, int transferTime) {

        if (time > maxTimeLimit) {
            return;
        }
        // transitTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        if (bestOveral.updateNewBestTime(stop, time)) {
            if(stop==18914){
                debugListedStops("transit to stop", round, stop);
            }

            final int stateIndex = findOrCreateStopIndex(stop);
            state.transferToStop(stateIndex, time, fromStop, transferTime);

            debugListedStops("transfer to stop", round, stop);
        }
    }

    int getPatternIndexForPreviousRound(int stop) {
        StopState state = stopPreviousRound(stop);
        int previousStop = state.boardStop();
        return previousStop == NOT_SET
                ? state.previousPattern()
                : stopPreviousRound(previousStop).previousPattern();
    }

    static void debugStopHeader(String title) {
        if(!DEBUG) return;
        System.err.printf("  S %-24s  -------- BEST OVERALL -------   %s%n", title, StopStateFlyWeight.HEADERS[0]);
        System.err.printf("  S %-24s  Rnd  Stop  Time C L Trans C L   %s%n", "", StopStateFlyWeight.HEADERS[1]);
    }

    void debugStop(String descr, int round, int stop) {
        if(!DEBUG) return;

        if(round < 0 || stop < 1) {
            System.err.printf("  S %-24s  %2d %6d  STOP DOES NOT EXIST!%n", descr, round, stop);
            return;
        }

        System.err.printf("  S %-24s  %2d %6d %s %s   %s%n",
                descr,
                round,
                stop,
                bestOveral.toString(stop),
                bestTransit.toString(stop),
                state.stopToString(stateStopIndex[round][stop])
        );
    }


    /* private methods */

    private boolean isCurrentRoundUpdated() {
        return !(bestOveral.isCurrentRoundEmpty() && bestTransit.isCurrentRoundEmpty());
    }

    private void debugListedStops(String descr, int round, int stop) {
        if (DEBUG && debugStops.contains(stop)) debugStop(descr, round, stop);
    }

    private int stopIndex(int stop) {
        return stateStopIndex[round][stop];
    }

    private int findOrCreateStopIndex(int stop) {
        if(stateStopIndex[round][stop] == 0) {
            stateStopIndex[round][stop] = state.nextAvailable();
        }
        return stopIndex(stop);
    }
}
