package com.conveyal.r5.profile.entur.rangeraptor;

import com.conveyal.r5.profile.entur.api.transit.AccessLeg;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.UnsignedIntIterator;

import java.util.Iterator;

/**
 * TODO TGR
 */
public interface WorkerState {
    void initNewDepartureForMinute(int nextMinuteDepartureTime);

    void setInitialTime(AccessLeg accessLeg, int nextMinuteDepartureTime);

    boolean isNewRoundAvailable();

    void prepareForNextRound();

    UnsignedIntIterator stopsTouchedPreviousRound();

    UnsignedIntIterator stopsTouchedByTransitCurrentRound();

    void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers);

    default void transitsForRoundComplete() {}

    default void transfersForRoundComplete() {}
}
