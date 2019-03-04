package com.conveyal.r5.profile.entur.rangeraptor.standard;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;

import java.util.Collection;


/**
 * The responsibility of this class is to wrap a {@link StopArrivalsState} and notify the
 * {@link StateDebugger} about all stop arrival events.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class DebugStopArrivalsState<T extends TripScheduleInfo> implements StopArrivalsState<T> {

    private final StopArrivalsState<T> delegate;
    private final StateDebugger<T> debug;

    /**
     * Create a Standard range raptor state for the given context
     */
    DebugStopArrivalsState(
            RoundProvider roundProvider,
            DebugHandlerFactory<T> dFactory,
            StopsCursor<T> stopsCursor,
            StopArrivalsState<T> delegate
    ) {
        this.debug = new StateDebugger<>(stopsCursor, roundProvider, dFactory.debugStopArrival());
        this.delegate = delegate;
    }

    @Override
    final public void setupIteration(int iterationDepartureTime) {
        debug.setIterationDepartureTime(iterationDepartureTime);
        delegate.setupIteration(iterationDepartureTime);
    }

    @Override
    public final void setInitialTime(final int stop, final int arrivalTime, int durationInSeconds) {
        delegate.setInitialTime(stop, arrivalTime, durationInSeconds);
        debug.accept(stop);
    }

    @Override
    public final Collection<Path<T>> extractPaths() {
        return delegate.extractPaths();
    }

    @Override
    public final int bestTimePreviousRound(int stop) {
        return delegate.bestTimePreviousRound(stop);
    }


    @Override
    public void setNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime, boolean newBestOverall) {
        debug.dropOldStateAndAcceptNewState(
                stop,
                () -> delegate.setNewBestTransitTime(stop, alightTime, trip, boardStop, boardTime, newBestOverall)
        );
    }

    @Override
    public void rejectNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        if (debug.isDebug(stop)) {
            debug.rejectTransit(stop, alightTime, trip, boardStop, boardTime);
        }
        delegate.rejectNewBestTransitTime(stop, alightTime, trip, boardStop, boardTime);
    }

    /**
     * Create paths for current iteration.
     */
    @Override
    public final void iterationComplete() {
        delegate.iterationComplete();
    }

    @Override
    public void setNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
        debug.dropOldStateAndAcceptNewState(
                transferLeg.stop(),
                () -> delegate.setNewBestTransferTime(fromStop, arrivalTime, transferLeg)
        );
    }

    @Override
    public void rejectNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
        if (debug.isDebug(transferLeg.stop())) {
            debug.rejectTransfer(fromStop, transferLeg, transferLeg.stop(), arrivalTime);
        }
        delegate.rejectNewBestTransferTime(fromStop, arrivalTime, transferLeg);
    }
}