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
    private final TransitCalculator calculator;

    /**
     * Keep track of current round and when to quit rounds iteration.
     */
    private final RoundTracker round;

    /**
     * The best times to reach each stop, whether via a transfer or via transit directly.
     */
    private final BestTimes bestTimes;


    /**
     * The list of egress stops, can be used to terminate the search when the stops are reached.
     */
    private final int[] egressStops;

    /**
     * create a BestTimes Range Raptor State for given context.
     */
    public BestTimesWorkerState(SearchContext<T> ctx) {
        this(
                ctx.nRounds(),
                ctx.transit().numberOfStops(),
                ctx.numberOfAdditionalTransfers(),
                ctx.egressStops(),
                ctx.calculator()
        );
    }

    protected BestTimesWorkerState(
            int nRounds,
            int nStops,
            int numberOfAdditionalTransfers,
            int[] egressStops,
            TransitCalculator calculator
    ) {
        this.round = new RoundTracker(nRounds, numberOfAdditionalTransfers);
        this.calculator = calculator;
        this.bestTimes = new BestTimes(nStops, calculator);
        this.egressStops = egressStops;
    }

    protected int bestTime(int stop) {
        return bestTimes.time(stop);
    }

    @Override
    public final void setupIteration(int iterationDepartureTime) {
        // clear all touched stops to avoid constant reëxploration
        bestTimes.prepareForNewIteration();
        round.setupIteration();
        setupIteration2(iterationDepartureTime);
    }

    /** Allow subclasses to setup initial iteration state by overriding this method. */
    protected void setupIteration2(int iterationDepartureTime) {}

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
    protected void setInitialTime(final int stop, final int arrivalTime, int durationInSeconds) { }

    @Override
    public final boolean isNewRoundAvailable() {
        updateRoundMaxLimitBasedOnDestinationArrival();
        return round.hasMoreRounds() && bestTimes.isCurrentRoundUpdated();
    }

    @Override
    public final void prepareForNextRound() {
        bestTimes.prepareForNextRound();
        round.prepareForNextRound();
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
     */
    @Override
    public final void transitToStop(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        if (exceedsTimeLimit(alightTime)) {
            return;
        }

        if (newTransitBestTime(stop, alightTime)) {
            // transitTimes upper bounds bestTimes
            final boolean newBestOverall = newOverallBestTime(stop, alightTime);
            setNewBestTransitTime(stop, alightTime, trip, boardStop, boardTime, newBestOverall);
        }
        else {
            rejectNewBestTransitTime(stop, alightTime, trip, boardStop, boardTime);
        }
    }

    /**
     * Override this to set the best transit state.
     * <p/>
     * PLEASE OVERRIDE!
     */
    protected void setNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime, boolean newBestOverall) { }

    /**
     * Transit is rejected, better time exist. Override to handle transit rejection.
     * <p/>
     * PLEASE OVERRIDE!
     */
    protected void rejectNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime) {}

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

    private void transferToStop(int arrivalTimeTransit, int fromStop, TransferLeg transferLeg) {
        // Use the calculator to make sure the calculation is done correct for a normal
        // forward search and a reverse search.
        final int arrivalTime = calculator.add(arrivalTimeTransit, transferLeg.durationInSeconds());

        if (exceedsTimeLimit(arrivalTime)) {
            return;
        }

        final int toStop = transferLeg.stop();

        // transitTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        if (newOverallBestTime(toStop, arrivalTime)) {
            setNewBestTransferTime(fromStop, arrivalTime, transferLeg);
        }
        else {
            rejectNewBestTransferTime(fromStop, arrivalTime, transferLeg);
        }
    }

    /**
     * Override this to set the best transfer state.
     * <p/>
     * PLEASE OVERRIDE!
     */

    protected void setNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) { }

    /**
     * Transfer is rejected, better time exist. Override to handle transit rejection.
     * <p/>
     * PLEASE OVERRIDE!
     */
    protected void rejectNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) { }

    protected final int round() {
        return round.round();
    }

    private boolean newTransitBestTime(int stop, int alightTime) {
        return bestTimes.transitUpdateNewBestTime(stop, alightTime);
    }

    private boolean newOverallBestTime(int stop, int alightTime) {
        return bestTimes.updateNewBestTime(stop, alightTime);
    }

    private boolean exceedsTimeLimit(int time) {
        return calculator.exceedsTimeLimit(time);
    }

    private void updateRoundMaxLimitBasedOnDestinationArrival() {
        if(destinationReachedLastRound()) {
            round.notifyDestinationReached();
        }
    }

    private boolean destinationReachedLastRound() {
        // This is fast enough, we could use a BitSet for egressStops, but it takes up more
        // memory and the performance is the same.
        for (int i = 0; i < egressStops.length; i++) {
            if(bestTimes.isStopReachedByTransitCurrentRound(i)) {
                return true;
            }
        }
        return false;
    }
}