package com.conveyal.r5.profile.entur.rangeraptor.standard;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.AccessLeg;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.DebugState;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.util.BitSetIterator;

import java.util.Collection;
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
public final class RangeRaptorWorkerState<T extends TripScheduleInfo> implements WorkerState {

    /**
     * @deprecated TODO TGR - Replace with pareto destination check
     */
    @Deprecated
    private static final int MAX_TRIP_DURATION_SECONDS = 20 * 60 * 60; // 20 hours


    /**
     * To debug a particular journey set DEBUG to true and add all visited stops in the debugStops list.
     */
    private final Stops<T> stops;
    private final StopsCursor<T> cursor;
    private final int nRounds;
    private final DestinationArrivals<T> results;

    private int round = 0;
    private int roundMax = -1;

    /**
     * Stop the search when the time excids the max time limit.
     */
    private int maxTimeLimit;


    /**
     * The best times to reach each stop, whether via a transfer or via transit directly.
     */
    private final BestTimes bestOverall;

    /**
     * Index to the best times for reaching stops via transit rather than via a transfer from another stop
     */
    private final BestTimes bestTransit;


    /**
     * create a RaptorState for a network with a particular number of stops, and a given maximum duration
     */
    RangeRaptorWorkerState(int nRounds, int nStops, RangeRaptorRequest request) {
        TransitCalculator calculator = new TransitCalculator(request);

        this.nRounds = nRounds;
        this.stops = new Stops<>(nRounds, nStops, request.egressLegs, this::handleEgressStopArrival);
        this.cursor = new StopsCursor<>(stops, calculator);

        this.bestOverall = new BestTimes(nStops);
        this.bestTransit = new BestTimes(nStops);
        this.results = new DestinationArrivals<>(nRounds, new StopsCursor<>(stops, calculator));
    }

    @Override
    public void gotoNextRound() {
        bestOverall.gotoNextRound();
        bestTransit.gotoNextRound();
        ++round;
        roundMax = Math.max(roundMax, round);
    }

    @Override
    public boolean isNewRoundAvailable() {
        final boolean moreRoundsToGo = round < nRounds - 1;
        return moreRoundsToGo && isCurrentRoundUpdated();
    }

    public Collection<Path<T>> paths() {
        return results.paths();
    }

    boolean isStopReachedInPreviousRound(int stop) {
        return bestOverall.isReachedLastRound(stop);
    }

    BitSetIterator bestStopsTouchedLastRoundIterator() {
        return bestOverall.stopsReachedLastRound();
    }

    @Override
    public BitSetIterator stopsTouchedByTransitCurrentRound() {
        return bestTransit.stopsReachedCurrentRound();
    }

    int bestTimePreviousRound(int stop) {
        return stops.get(round - 1, stop).time();
    }

    @Override
    public void initNewDepartureForMinute(int departureTime) {
        // TODO TGR - Set max limit to 5 days for now, replace this with a pareto check against the
        // TODO TGR - destination location values.
        maxTimeLimit = departureTime + MAX_TRIP_DURATION_SECONDS;

        // clear all touched stops to avoid constant reëxploration
        bestOverall.clearCurrent();
        bestTransit.clearCurrent();
        round = 0;
    }

    @Override
    public void setInitialTime(AccessLeg accessLeg, int fromTime) {
        final int accessDurationInSeconds = accessLeg.durationInSeconds();
        final int stop = accessLeg.stop();
        final int arrivalTime = fromTime + accessDurationInSeconds;

        stops.setInitialTime(round, stop, arrivalTime);
        bestOverall.setTime(stop, arrivalTime);
        debugStop(round, stop);
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the transitTime
     */
    void transitToStop(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        if (alightTime > maxTimeLimit) {
            return;
        }

        if (bestTransit.updateNewBestTime(stop, alightTime)) {

            // transitTimes upper bounds bestTimes
            final boolean newBestOverall = bestOverall.updateNewBestTime(stop, alightTime);

            stops.transitToStop(round, stop, alightTime, boardStop, boardTime, trip, newBestOverall);

            // skip: transferTimes
            debugStop(round, stop);
        }
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the nonTransferTime
     */
    @Override
    public void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers) {
        while (transfers.hasNext()) {
            transferToStop(fromStop, transfers.next());
        }
    }

    /**
     * Create the optimal path to each stop in the transit network, based on the given McRaptorState.
     */
    void addPathsForCurrentIteration() {
        results.addPathsForCurrentIteration();
    }

    public void debugStopHeader(String title) {
        DebugState.debugStopHeader(title, "Best     C P | Transit  C P");
    }


    /* private methods */

    private void transferToStop(int fromStop, TransferLeg transferLeg) {
        final int toStop = transferLeg.stop();

        int arrivalTime = bestTransit.time(fromStop) + transferLeg.durationInSeconds();

        if (arrivalTime > maxTimeLimit) {
            return;
        }
        // transitTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        if (bestOverall.updateNewBestTime(toStop, arrivalTime)) {
            stops.transferToStop(round, fromStop, transferLeg, arrivalTime);

            debugStop(round, toStop);
        }
    }

    private boolean isCurrentRoundUpdated() {
        return !(bestOverall.isCurrentRoundEmpty() && bestTransit.isCurrentRoundEmpty());
    }

    private void debugStop(int round, int stop) {
        if (DebugState.isDebug(stop)) {
            DebugState.debugStop(
                    cursor.stop(round, stop),
                    bestOverall.toString(stop) + " | " + bestTransit.toString(stop)
            );
        }
    }

    private void handleEgressStopArrival(EgressStopArrivalState<T> arrival) {
        results.add(arrival);
    }
}
