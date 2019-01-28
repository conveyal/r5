package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

/**
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class StateDebugger<T extends TripScheduleInfo> implements DebugState<T> {
    private final StopsCursor<T> cursor;
    private final DebugHandler<StopArrivalView<T>> debugHandlerStopArrivals;

    StateDebugger(StopsCursor<T> cursor, DebugHandler<StopArrivalView<T>> debugHandlerStopArrivals) {
        this.cursor = cursor;
        this.debugHandlerStopArrivals = debugHandlerStopArrivals;
    }

    public void setIterationDepartureTime(int iterationDepartureTime) {
        debugHandlerStopArrivals.setIterationDepartureTime(iterationDepartureTime);
    }

    public boolean isDebug(int stop) {
        return debugHandlerStopArrivals.isDebug(stop);
    }

    public void accept(int round, int stop) {
        debugHandlerStopArrivals.accept(cursor.stop(round, stop), null);
    }

    public void drop(int round, int stop) {
        if(cursor.exist(round, stop)) {
            debugHandlerStopArrivals.drop(cursor.stop(round, stop), null);
        }
    }

    private void reject(StopArrivalView<T> arrival) {
        debugHandlerStopArrivals.reject(arrival, null);
    }

    public void dropOldStateAndAcceptNewState(int round, int stop, Runnable body) {
        if (isDebug(stop)) {
            drop(round, stop);
            body.run();
            accept(round, stop);
        } else {
            body.run();
        }
    }

    public void rejectTransit(int round, int stop, int alightTime, T trip, int boardStop, int boardTime) {
        StopArrivalState<T> arrival = new StopArrivalState<>();
        arrival.arriveByTransit(alightTime, boardStop, boardTime, trip);
        reject(new StopArrivalViewAdapter.Transit<>(round, stop, arrival, cursor));
    }

    public void rejectTransfer(int round, int fromStop, TransferLeg transferLeg, int toStop, int arrivalTime) {
        StopArrivalState<T> arrival = new StopArrivalState<>();
        arrival.transferToStop(fromStop, arrivalTime, transferLeg.durationInSeconds());
        reject(new StopArrivalViewAdapter.Transfer<>(round, toStop, arrival, cursor));
    }
}
