package com.conveyal.r5.profile.entur.rangeraptor.standard;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
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
public final class StdRangeRaptorWorkerState<T extends TripScheduleInfo> implements StdWorkerState<T> {
    /**
     * @deprecated TODO TGR - Replace with pareto destination check
     */
    @Deprecated
    private static final int MAX_TRIP_DURATION_SECONDS = 20 * 60 * 60; // 20 hours

    /**
     * To debug a particular journey set DEBUG to true and add all visited stops in the debugStops list.
     */
    private final Stops<T> stops;
    private final int nRounds;
    private final DestinationArrivals<T> results;
    private final TransitCalculator calculator;

    private int round = 0;
    private int roundMax = -1;

    /**
     * Stop the search when the time exceeds the max time limit.
     */
    private final int timeLimit;

    /**
     * The best times to reach each stop, whether via a transfer or via transit directly.
     */
    private final BestTimes bestTimes;


    /**
     * Debug state events
     */
    private final DebugState<T> debug;


    /**
     * Create a Standard range raptor state for the given context
     */
    public StdRangeRaptorWorkerState(SearchContext<T> c) {
        this(c.nRounds(), c.nStops(), c.calculator(), c.debugFactory(), c.request());
    }

    private StdRangeRaptorWorkerState(
            int nRounds, int nStops,
            TransitCalculator calculator,
            DebugHandlerFactory<T> dFactory,
            RangeRaptorRequest<T> request
    ) {
        this.nRounds = nRounds;
        this.calculator = calculator;
        this.timeLimit =  calculator.add(request.toTime(), MAX_TRIP_DURATION_SECONDS);
        this.stops = new Stops<>(nRounds, nStops, request.egressLegs(), this::handleEgressStopArrival);
        this.bestTimes = new BestTimes(nStops, calculator);

        this.results = new DestinationArrivals<>(nRounds, new StopsCursor<>(stops, calculator), dFactory);
        this.debug = request.debug().isDebug()
                ? new StateDebugger<>(new StopsCursor<>(stops, calculator), dFactory.debugStopArrival())
                : DebugState.noop();
    }

    @Override
    public void iterationSetup(int iterationDepartureTime) {
        debug.setIterationDepartureTime(iterationDepartureTime);
        results.setIterationDepartureTime(iterationDepartureTime);

        // clear all touched stops to avoid constant reëxploration
        bestTimes.prepareForNewIteration();
        round = 0;
    }

    @Override
    public void setInitialTime(TransferLeg accessEgressLeg, int iterationDepartureTime) {
        final int stop = accessEgressLeg.stop();
        final int durationInSeconds = accessEgressLeg.durationInSeconds();
        final int arrivalTime = calculator.add(iterationDepartureTime, durationInSeconds);

        stops.setInitialTime(round, stop, arrivalTime, durationInSeconds);
        bestTimes.setAccessStopTime(stop, arrivalTime);
        debug.accept(round, stop);
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

    @Override
    public Collection<Path<T>> extractPaths() {
        return results.paths();
    }

    @Override
    public boolean isStopReachedInPreviousRound(int stop) {
        return bestTimes.isStopReachedLastRound(stop);
    }

    @Override
    public int bestTimePreviousRound(int stop) {
        return stops.get(round - 1, stop).time();
    }


    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the transitTime
     */
    @Override
    public void transitToStop(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        if (exceedsTimeLimit(alightTime)) {
            return;
        }

        if (bestTimes.transitUpdateNewBestTime(stop, alightTime)) {

            // transitTimes upper bounds bestTimes
            final boolean newBestOverall = bestTimes.updateNewBestTime(stop, alightTime);

            debug.dropOldStateAndAcceptNewState(
                    round,
                    stop,
                    () -> stops.transitToStop(round, stop, alightTime, boardStop, boardTime, trip, newBestOverall)
            );
        }
        else if(debug.isDebug(stop)) {
            debug.rejectTransit(round, stop, alightTime, trip, boardStop, boardTime);
        }
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the nonTransferTime
     */
    @Override
    public void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers) {

        int arrivalTimeTransit = stops.get(round, fromStop).transitTime();

        while (transfers.hasNext()) {
            transferToStop(arrivalTimeTransit, fromStop, transfers.next());
        }
    }

    /**
     * Create paths for current iteration.
     */
    @Override
    public void iterationComplete() {
        results.addPathsForCurrentIteration();
    }


    /* private methods */

    private void transferToStop(int arrivalTimeTransit, int fromStop, TransferLeg transferLeg) {
        final int arrivalTime = arrivalTimeTransit + transferLeg.durationInSeconds();

        if (exceedsTimeLimit(arrivalTime)) {
            return;
        }

        final int toStop = transferLeg.stop();

        // transitTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        if (bestTimes.updateNewBestTime(toStop, arrivalTime)) {

            debug.dropOldStateAndAcceptNewState(
                    round,
                    toStop,
                    () -> stops.transferToStop(round, fromStop, transferLeg, arrivalTime)
            );
        }
        else if(debug.isDebug(toStop)) {
            debug.rejectTransfer(round, fromStop, transferLeg, toStop, arrivalTime);
        }
    }

    private boolean exceedsTimeLimit(int alightTime) {
        return calculator.isBest(timeLimit, alightTime);
    }

    private void handleEgressStopArrival(EgressStopArrivalState<T> arrival) {
        results.add(arrival);
    }
}
