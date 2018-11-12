package com.conveyal.r5.profile.entur.rangeraptor;

import com.conveyal.r5.profile.entur.api.AccessLeg;
import com.conveyal.r5.profile.entur.api.TransferLeg;
import com.conveyal.r5.profile.entur.util.BitSetIterator;

import java.util.Iterator;

public interface WorkerState {
    void initNewDepartureForMinute(int nextMinuteDepartureTime);

    void setInitialTime(AccessLeg accessLeg, int nextMinuteDepartureTime, int boardSlackInSeconds);

    void debugStopHeader(String header);

    boolean isNewRoundAvailable();

    void gotoNextRound();

    BitSetIterator stopsTouchedByTransitCurrentRound();

    void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers);
}
