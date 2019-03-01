package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

/**
 *  Encapsulate state debug operations. The default implementation is to do nothing.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class StateDebugger<T extends TripScheduleInfo> implements DebugState<T> {
    private final StopsCursor<T> cursor;
    private final RoundProvider roundProvider;
    private final DebugHandler<StopArrivalView<T>> debugHandlerStopArrivals;

    StateDebugger(
            StopsCursor<T> cursor,
            RoundProvider roundProvider,
            DebugHandler<StopArrivalView<T>> debugHandlerStopArrivals
    ) {
        this.cursor = cursor;
        this.roundProvider = roundProvider;
        this.debugHandlerStopArrivals = debugHandlerStopArrivals;
    }

    @Override
    public void setIterationDepartureTime(int iterationDepartureTime) {
        debugHandlerStopArrivals.setIterationDepartureTime(iterationDepartureTime);
    }

    @Override
    public boolean isDebug(int stop) {
        return debugHandlerStopArrivals.isDebug(stop);
    }

    @Override
    public void accept(int stop) {
        debugHandlerStopArrivals.accept(cursor.stop(round(), stop), null);
    }

    @Override
    public void drop(int stop) {
        if(cursor.exist(round(), stop)) {
            debugHandlerStopArrivals.drop(cursor.stop(round(), stop), null);
        }
    }

    @Override
    public void dropOldStateAndAcceptNewState(int stop, Runnable body) {
        if (isDebug(stop)) {
            drop(stop);
            body.run();
            accept(stop);
        } else {
            body.run();
        }
    }

    @Override
    public void rejectTransit(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        StopArrivalState<T> arrival = new StopArrivalState<>();
        arrival.arriveByTransit(alightTime, boardStop, boardTime, trip);
        reject(new StopArrivalViewAdapter.Transit<>(round(), stop, arrival, cursor));
    }

    @Override
    public void rejectTransfer(int fromStop, TransferLeg transferLeg, int toStop, int arrivalTime) {
        StopArrivalState<T> arrival = new StopArrivalState<>();
        arrival.transferToStop(fromStop, arrivalTime, transferLeg.durationInSeconds());
        reject(new StopArrivalViewAdapter.Transfer<>(round(), toStop, arrival, cursor));
    }


    /* Private methods */

    private void reject(StopArrivalView<T> arrival) {
        debugHandlerStopArrivals.reject(arrival, null);
    }

    private int round() {
        return roundProvider.round();
    }

}
