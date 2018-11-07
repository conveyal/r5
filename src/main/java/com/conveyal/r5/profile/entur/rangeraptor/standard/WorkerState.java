package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.util.BitSetIterator;

public interface WorkerState {
    void initNewDepartureForMinute(int nextMinuteDepartureTime);

    void setInitialTime(StopArrival stopArrival, int nextMinuteDepartureTime, int boardSlackInSeconds);

    void debugStopHeader(String header);

    boolean isNewRoundAvailable();

    void gotoNextRound();

    BitSetIterator stopsTouchedByTransitCurrentRound();

    void transferToStop(int fromStop, StopArrival transfer);
}
