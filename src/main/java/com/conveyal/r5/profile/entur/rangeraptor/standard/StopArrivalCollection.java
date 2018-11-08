package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

public interface StopArrivalCollection<T extends TripScheduleInfo> {

    void setInitialTime(int round, int stop, int time);

    void transitToStop(int round, int stop, int time, int boardStop, int boardTime, T trip, boolean bestTime);

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    void transferToStop(int round, int fromStop, StopArrival stop, int arrivalTime);


    StopArrivalCursor<T> newCursor();
}
