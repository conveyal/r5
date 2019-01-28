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
public class BestTimesWorkerState<T extends TripScheduleInfo> implements StdWorkerState<T> {

    /**
     * @deprecated TODO TGR - Replace with pareto destination check
     */
    @Deprecated
    private static final int MAX_TRIP_DURATION_SECONDS = 20 * 60 * 60; // 20 hours

    private final int nRounds;
    private final TransitCalculator calculator;

    private int round = 0;
    private int roundMax = -1;

    /**
     * Stop the search when the time exceeds the max time limit.
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

    BestTimesWorkerState(int nRounds, int nStops, TransitCalculator calculator) {
        this.nRounds = nRounds;
        this.calculator = calculator;
        this.bestTimes = new BestTimes(nStops, calculator);
    }

    @Override
    public final void setupIteration(int iterationDepartureTime) {
        // TODO TGR - Set max limit to 5 days for now, replace this with a pareto check against the
        // TODO TGR - destination location values.
        timeLimit = calculator.add(iterationDepartureTime, MAX_TRIP_DURATION_SECONDS);
        // clear all touched stops to avoid constant reëxploration
        bestTimes.prepareForNewIteration();
        round = 0;
        setupIteration2(iterationDepartureTime);
    }

    /** Allow subclasses to setup initial iteration state by overriding this method. */
    void setupIteration2(int iterationDepartureTime) {}

    @Override
    public final void setInitialTimeForIteration(TransferLeg accessEgressLeg, int iterationDepartureTime) {
        int durationInSeconds = accessEgressLeg.durationInSeconds();
        int stop = accessEgressLeg.stop();
        // The time of arrival at the given stop for the current iteration
        // (or departure time at the last stop if we search backwards).
        int arrivalTime = calculator.add(iterationDepartureTime, durationInSeconds);

        bestTimes.setAccessStopTime(stop, arrivalTime);
        setInitialTime(stop, arrivalTime, durationInSeconds);
    }

    /** Allow subclasses to setup initial access/egress time  by overriding this method. */
    void setInitialTime(final int stop, final int arrivalTime, int durationInSeconds) { }

    @Override
    public final boolean isNewRoundAvailable() {
        final boolean moreRoundsToGo = round < nRounds - 1;
        return moreRoundsToGo && bestTimes.isCurrentRoundUpdated();
    }

    @Override
    public final void prepareForNextRound() {
        bestTimes.prepareForNextRound();
        ++round;
        roundMax = Math.max(roundMax, round);
    }

    @Override
    public final BitSetIterator stopsTouchedByTransitCurrentRound() {
        return bestTimes.transitStopsReachedCurrentRound();
    }

    @Override
    public final IntIterator stopsTouchedPreviousRound() {
        return bestTimes.stopsReachedLastRound();
    }


    @Override
    public final boolean isStopReachedInPreviousRound(int stop) {
        return bestTimes.isStopReachedLastRound(stop);
    }

    /**
     * Return the "best time" found in the previous round. This is used to calculate the board/alight
     * time in the next round.
     * <p/>
     * PLEASE OVERRIDE!
     * <p/>
     * The implementation here is not correct - please override if you plan to use any result paths
     * or "rounds" as "number of transfers". The implementation is OK if the only thing you care
     * about is the "arrival time".
     */
    @Override
    public int bestTimePreviousRound(int stop) {
        // This is a simplification, *bestTimes* might get updated during the current round;
        // Hence leading to a new boarding from the same stop in the same round.
        // If we do not count rounds or track paths, this is OK. But be sure to override this
        // method with the best time from the previous round if you care about number of
        // transfers and results paths.
        return bestTimes.time(stop);
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the transitTime.
     * <p/>
     * PLEASE OVERRIDE FOR MORE SPECIFIC BEHAVIOR!
     * <p/>
     * The implementation can be copied and alterd into a sub classe
     */
    @Override
    public void transitToStop(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        if (exceedsTimeLimit(alightTime)) {
            return;
        }

        if (newTransitBestTime(stop, alightTime)) {
            // transitTimes upper bounds bestTimes
            newOverallBestTime(stop, alightTime);
        }
    }

    /**
     * Set the arrival time at all transit stop if time is optimal for the given list of transfers.
     */
    @Override
    public final void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers) {
        int arrivalTimeTransit = bestTimes.transitTime(fromStop);
        while (transfers.hasNext()) {
            transferToStop(arrivalTimeTransit, fromStop, transfers.next());
        }
    }

    /**
     * PLEASE OVERRIDE FOR MORE SPECIFIC BEHAVIOR!
     * <p/>
     * The implementation can be copied and altered into a sub-class.
     */
    void transferToStop(int arrivalTimeTransit, int fromStop, TransferLeg transferLeg) {
        final int arrivalTime = arrivalTimeTransit + transferLeg.durationInSeconds();

        if (exceedsTimeLimit(arrivalTime)) {
            return;
        }

        final int toStop = transferLeg.stop();

        // transitTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        newOverallBestTime(toStop, arrivalTime);
    }

    final int round() {
        return round;
    }

    final boolean newTransitBestTime(int stop, int alightTime) {
        return bestTimes.transitUpdateNewBestTime(stop, alightTime);
    }

    final boolean newOverallBestTime(int stop, int alightTime) {
        return bestTimes.updateNewBestTime(stop, alightTime);
    }

    final boolean exceedsTimeLimit(int alightTime) {
        return calculator.isBest(timeLimit, alightTime);
    }
}