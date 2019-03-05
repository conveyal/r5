package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

/**
 * Send debug events to the {@link DebugHandler} using the {@link StopsCursor}.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class StateDebugger<T extends TripScheduleInfo> {
    private final StopsCursor<T> cursor;
    private final RoundProvider roundProvider;
    private final DebugHandler<StopArrivalView<T>> debugHandlerStopArrivals;

    StateDebugger(
            StopsCursor<T> cursor,
            RoundProvider roundProvider,
            DebugHandlerFactory<T> dFactory
    ) {
        this.cursor = cursor;
        this.roundProvider = roundProvider;
        this.debugHandlerStopArrivals = dFactory.debugStopArrival();
    }

    void acceptAccess(int stop) {
        if(isDebug(stop)) {
            accept(stop);
        }
    }

    void dropOldStateAndAcceptNewState(int stop, Runnable body) {
        if (isDebug(stop)) {
            drop(stop);
            body.run();
            accept(stop);
        } else {
            body.run();
        }
    }

    void rejectTransit(int alightStop, int alightTime, T trip, int boardStop, int boardTime) {
        if (isDebug(alightStop)) {
            StopArrivalState<T> arrival = new StopArrivalState<>();
            arrival.arriveByTransit(alightTime, boardStop, boardTime, trip);
            reject(new StopArrivalViewAdapter.Transit<>(round(), alightStop, arrival, cursor));
        }
    }

    void rejectTransfer(int fromStop, TransferLeg transferLeg, int toStop, int arrivalTime) {
        if (isDebug(transferLeg.stop())) {
            StopArrivalState<T> arrival = new StopArrivalState<>();
            arrival.transferToStop(fromStop, arrivalTime, transferLeg.durationInSeconds());
            reject(new StopArrivalViewAdapter.Transfer<>(round(), toStop, arrival, cursor));
        }
    }


    /* Private methods */

    private boolean isDebug(int stop) {
        return debugHandlerStopArrivals.isDebug(stop);
    }

    private void accept(int stop) {
        debugHandlerStopArrivals.accept(cursor.stop(round(), stop), null);
    }

    private void drop(int stop) {
        if(cursor.exist(round(), stop)) {
            debugHandlerStopArrivals.drop(cursor.stop(round(), stop), null);
        }
    }

    private void reject(StopArrivalView<T> arrival) {
        debugHandlerStopArrivals.reject(arrival, null);
    }

    private int round() {
        return roundProvider.round();
    }

}
