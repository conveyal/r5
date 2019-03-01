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

    /**
     * Return TRUE if at least one new destination arrival is accepted at the destination in
     * the current round. If no paths to the destination is found in the current round, FALSE
     * is returned. And last, if a new path is found in the current round - reaching the
     * destination - but the path is NOT accepted(not pareto-optimal), then FALSE is returned.
     * <p/>
     * This method is called at the end of each round.
     */
    boolean isDestinationReachedInCurrentRound();
}
