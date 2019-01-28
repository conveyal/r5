package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerState;

public interface StdWorkerState<T extends TripScheduleInfo> extends WorkerState<T> {

    boolean isStopReachedInPreviousRound(int stop);

    int bestTimePreviousRound(int stop);

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the transitTime
     */
    void transitToStop(int stop, int alightTime, T trip, int boardStop, int boardTime);
}