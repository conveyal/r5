package com.conveyal.r5.profile.entur.rangeraptor.standard;


import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.util.BitSetIterator;

import java.util.Iterator;


/**
 * Tracks the state of a Range Raptor search, specifically the best arrival times at each transit stop at the end of a
 * particular round, along with associated data to reconstruct paths etc.
 * <p>
 * This is grouped into a separate class (rather than just having the fields in the raptor worker class) because we
 * want to separate the logic of maintaining stop arrival state and performing the steps of the algorithm. This
 * also make it possible to have more than one state implementation, which have ben used in the past to test different
 * memory optimizations.
 * <p>
 * Note that this represents the entire state of the Range Raptor search for all rounds, rather than the state at
 * a particular transit stop.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class BestTimesWorkerState<T extends TripScheduleInfo> implements StdWorkerState<T> {

    /**
     * @deprecated TODO TGR - Replace with pareto destination check
     */
    @Deprecated
    private static final int MAX_TRIP_DURATION_SECONDS = 20 * 60 * 60; // 20 hours


    private final int nRounds;
    private int round = 0;
    private int roundMax = -1;

    /**
     * Stop the search when the time excids the max time limit.
     */
    private int timeLimit;

    /**
     * The best times to reach each stop, whether via a transfer or via transit directly.
     */
    private final BestTimes bestTimes;


    /**
     * create a BestTimes Range Raptor State for given context.
     */
    public BestTimesWorkerState(SearchContext<T> ctx) {
        this(ctx.nRounds(), ctx.transit().numberOfStops(), ctx.calculator());
    }

    private BestTimesWorkerState(int nRounds, int nStops, TransitCalculator calculator) {
        this.nRounds = nRounds;
        this.bestTimes = new BestTimes(nStops, calculator);
    }

    @Override
    public void iterationSetup(int iterationDepartureTime) {
        // TODO TGR - Set max limit to 5 days for now, replace this with a pareto check against the
        // TODO TGR - destination location values.
        timeLimit = iterationDepartureTime + MAX_TRIP_DURATION_SECONDS;

        // clear all touched stops to avoid constant reëxploration
        bestTimes.prepareForNewIteration();
        round = 0;
    }

    @Override
    public void setInitialTime(TransferLeg accessLeg, int iterationDepartureTime) {
        final int accessDurationInSeconds = accessLeg.durationInSeconds();
        final int stop = accessLeg.stop();
        final int arrivalTime = iterationDepartureTime + accessDurationInSeconds;

        bestTimes.setAccessStopTime(stop, arrivalTime);
    }

    @Override
    public boolean isNewRoundAvailable() {
        final boolean moreRoundsToGo = round < nRounds - 1;
        return moreRoundsToGo && bestTimes.isCurrentRoundUpdated();
    }

    @Override
    public void prepareForNextRound() {
        bestTimes.prepareForNextRound();
        ++round;
        roundMax = Math.max(roundMax, round);
    }

    @Override
    public BitSetIterator stopsTouchedByTransitCurrentRound() {
        return bestTimes.transitStopsReachedCurrentRound();
    }

    @Override
    public IntIterator stopsTouchedPreviousRound() {
        return bestTimes.stopsReachedLastRound();
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the nonTransferTime
     */
    @Override
    public void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers) {

        int arrivalTimeTransit = bestTimes.transitTime(fromStop);

        while (transfers.hasNext()) {
            transferToStop(arrivalTimeTransit, fromStop, transfers.next());
        }
    }

    @Override
    public boolean isStopReachedInPreviousRound(int stop) {
        return bestTimes.isStopReachedLastRound(stop);
    }

    @Override
    public int bestTimePreviousRound(int stop) {
        return bestTimes.time(stop);
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the transitTime
     */
    @Override
    public void transitToStop(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        if (alightTime > timeLimit) {
            return;
        }

        if (bestTimes.transitUpdateNewBestTime(stop, alightTime)) {

            // transitTimes upper bounds bestTimes
            bestTimes.updateNewBestTime(stop, alightTime);
        }
    }


    /* private methods */

    private void transferToStop(int arrivalTimeTransit, int fromStop, TransferLeg transferLeg) {
        final int arrivalTime = arrivalTimeTransit + transferLeg.durationInSeconds();

        if (arrivalTime > timeLimit) {
            return;
        }

        final int toStop = transferLeg.stop();

        // transitTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        bestTimes.updateNewBestTime(toStop, arrivalTime);
    }
}