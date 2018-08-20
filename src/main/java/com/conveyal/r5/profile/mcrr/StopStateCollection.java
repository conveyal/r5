package com.conveyal.r5.profile.mcrr;

public interface StopStateCollection {

    void setInitalTime(int round, int stop, int time);

    void transitToStop(int round, int stop, int time, int fromPattern, int boardStop, int tripIndex, int boardTime, boolean bestTime);

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    void transferToStop(int round, int stop, int time, int fromStop, int transferTime);


    StopStateCursor newCursor();
}
