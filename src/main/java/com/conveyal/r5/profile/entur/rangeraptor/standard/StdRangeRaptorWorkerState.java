package com.conveyal.r5.profile.entur.rangeraptor.standard;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;

import java.util.Collection;


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
public final class StdRangeRaptorWorkerState<T extends TripScheduleInfo> extends BestTimesWorkerState<T> {

    private final Stops<T> stops;
    private final DestinationArrivals<T> results;
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
        super(nRounds, nStops, calculator);
        this.stops = new Stops<>(nRounds, nStops, request.egressLegs(), this::handleEgressStopArrival);
        this.results = new DestinationArrivals<>(nRounds, new StopsCursor<>(stops, calculator), dFactory);
        this.debug = request.debug().isDebug()
                ? new StateDebugger<>(new StopsCursor<>(stops, calculator), dFactory.debugStopArrival())
                : DebugState.noop();
    }

    @Override
    final public void setupIteration2(int iterationDepartureTime) {
        debug.setIterationDepartureTime(iterationDepartureTime);
        results.setIterationDepartureTime(iterationDepartureTime);
    }

    @Override
    final void setInitialTime(final int stop, final int arrivalTime, int durationInSeconds) {
        stops.setInitialTime(round(), stop, arrivalTime, durationInSeconds);
        debug.accept(round(), stop);
    }

    @Override
    public final Collection<Path<T>> extractPaths() {
        return results.paths();
    }

    @Override
    public final int bestTimePreviousRound(int stop) {
        return stops.get(round() - 1, stop).time();
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the transitTime
     */
    @Override
    public final void transitToStop(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        if (exceedsTimeLimit(alightTime)) {
            return;
        }

        if (newTransitBestTime(stop, alightTime)) {
            // transitTimes upper bounds bestTimes
            final boolean newBestOverall = newOverallBestTime(stop, alightTime);

            debug.dropOldStateAndAcceptNewState(
                    round(),
                    stop,
                    () -> stops.transitToStop(round(), stop, alightTime, boardStop, boardTime, trip, newBestOverall)
            );
        }
        else if(debug.isDebug(stop)) {
            debug.rejectTransit(round(), stop, alightTime, trip, boardStop, boardTime);
        }
    }

    /**
     * Create paths for current iteration.
     */
    @Override
    public final void iterationComplete() {
        results.addPathsForCurrentIteration();
    }

    @Override
    final void transferToStop(int arrivalTimeTransit, int fromStop, TransferLeg transferLeg) {
        final int arrivalTime = arrivalTimeTransit + transferLeg.durationInSeconds();

        if (exceedsTimeLimit(arrivalTime)) {
            return;
        }

        final int toStop = transferLeg.stop();

        // transitTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        if (newOverallBestTime(toStop, arrivalTime)) {

            debug.dropOldStateAndAcceptNewState(
                    round(),
                    toStop,
                    () -> stops.transferToStop(round(), fromStop, transferLeg, arrivalTime)
            );
        }
        else if(debug.isDebug(toStop)) {
            debug.rejectTransfer(round(), fromStop, transferLeg, toStop, arrivalTime);
        }
    }

    private void handleEgressStopArrival(EgressStopArrivalState<T> arrival) {
        results.add(arrival);
    }
}