package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;


/**
 * Encapsulate state debug operations. The default implementation is to do nothing.
 *
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
interface DebugState<T extends TripScheduleInfo> {

    /** Create a implementation with does nothing.  */
    static <T extends TripScheduleInfo> DebugState<T> noop() { return new DebugState<T>() {}; }

    default void setIterationDepartureTime(int iterationDepartureTime) { }

    default boolean isDebug(int stop) {
        return false;
    }

    default void accept(int stop) { }

    default void rejectTransit(int stop, int alightTime, T trip, int boardStop, int boardTime) { }

    default void rejectTransfer(int fromStop, TransferLeg transferLeg, int toStop, int arrivalTime) { }

    default void drop(int stop) { }

    default void dropOldStateAndAcceptNewState(int stop, Runnable body) {
        body.run();
    }
}
