package com.conveyal.r5.profile.mcrr;

public interface WorkerState {
    void initNewDepatureForMinute(int nextMinuteDepartureTime);

    void setInitialTime(int stop, int nextMinuteDepartureTime, int time);

    void debugStopHeader(String header);

    boolean isNewRoundAvailable();

    void gotoNextRound();

    BitSetIterator stopsTouchedByTransitCurrentRound();

    void transferToStop(int fromStop, int toStop, int time);
}
