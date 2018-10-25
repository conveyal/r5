package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.StopArrival;

public interface StopStateCollection {

    void setInitialTime(int round, int stop, int time);

    void transitToStop(int round, int stop, int time, int fromPattern, int boardStop, int tripIndex, int boardTime, boolean bestTime);

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    void transferToStop(int round, int fromStop, StopArrival stop, int arrivalTime);


    StopStateCursor newCursor();
}
