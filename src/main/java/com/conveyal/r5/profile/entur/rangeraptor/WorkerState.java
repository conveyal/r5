package com.conveyal.r5.profile.entur.rangeraptor;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

/**
 * TODO TGR
 */
public interface WorkerState<T extends TripScheduleInfo> {

    void setupIteration(int iterationDepartureTime);

    void setInitialTimeForIteration(TransferLeg accessEgressLeg, int iterationDepartureTime);

    boolean isNewRoundAvailable();

    void prepareForNextRound();

    IntIterator stopsTouchedPreviousRound();

    IntIterator stopsTouchedByTransitCurrentRound();

    default void transitsForRoundComplete() {}

    void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers);

    default void transfersForRoundComplete() {}

    default void iterationComplete() {}

    /**
     * Extract paths after the search is complete. This method is optional,
     * returning an empty set by default.
     *
     * @return return all paths found in the search.
     */
    default Collection<Path<T>> extractPaths() { return Collections.emptyList(); }
}
