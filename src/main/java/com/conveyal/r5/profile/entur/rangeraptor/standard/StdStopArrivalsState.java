package com.conveyal.r5.profile.entur.rangeraptor.standard;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;

import java.util.Collection;


/**
 * Tracks the state necessary to construct paths at the end of each iteration.
 * <p/>
 * This class find the pareto optimal paths with respect to: rounds, arrival time and total travel time.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class StdStopArrivalsState<T extends TripScheduleInfo> implements StopArrivalsState<T> {

    private final Stops<T> stops;
    private final DestinationArrivals<T> results;

    /**
     * Create a Standard Range Raptor state for the given context. If debugging is enabled,
     * the stop arrival state is wrapped.
     */
    public static <T extends TripScheduleInfo> StopArrivalsState<T> createStdWorkerState(SearchContext<T> c) {
        StdStopArrivalsState<T> state = new StdStopArrivalsState<>(
                c.nRounds(),
                c.nStops(),
                c.roundProvider(),
                c.calculator(),
                c.debugFactory(),
                c.egressLegs()
        );

        if (c.debugRequest().isDebug()) {
            return new DebugStopArrivalsState<>(
                    c.roundProvider(),
                    c.debugFactory(),
                    state.cursor(c.calculator()),
                    state
            );
        } else {
            return state;
        }
    }

    private StdStopArrivalsState(
            int nRounds,
            int nStops,
            RoundProvider roundProvider,
            TransitCalculator calculator,
            DebugHandlerFactory<T> dFactory,
            Collection<TransferLeg> egressLegs
    ) {
        this.stops = new Stops<>(nRounds, nStops, egressLegs, roundProvider, this::handleEgressStopArrival);
        this.results = new DestinationArrivals<>(nRounds, calculator, cursor(calculator), dFactory);
    }

    @Override
    final public void setupIteration(int iterationDepartureTime) {
        results.setIterationDepartureTime(iterationDepartureTime);
    }

    @Override
    public final void setInitialTime(final int stop, final int arrivalTime, int durationInSeconds) {
        stops.setInitialTime(stop, arrivalTime, durationInSeconds);
    }

    @Override
    public final Collection<Path<T>> extractPaths() {
        return results.paths();
    }

    @Override
    public final int bestTimePreviousRound(int stop) {
        return stops.bestTimePreviousRound(stop);
    }


    @Override
    public void setNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime, boolean newBestOverall) {
        stops.transitToStop(stop, alightTime, boardStop, boardTime, trip, newBestOverall);
    }

    @Override
    public void rejectNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime) {
    }

    /**
     * Create paths for current iteration.
     */
    @Override
    public final void iterationComplete() {
        results.addPathsForCurrentIteration();
    }

    @Override
    public void setNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
        stops.transferToStop(fromStop, transferLeg, arrivalTime);
    }

    @Override
    public void rejectNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
    }


    /* private methods */

    private StopsCursor<T> cursor(TransitCalculator calculator) {
        return new StopsCursor<>(stops, calculator);
    }

    private void handleEgressStopArrival(EgressStopArrivalState<T> arrival) {
        results.add(arrival);
    }
}