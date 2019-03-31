package com.conveyal.r5.otp2.rangeraptor;

import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.transit.IntIterator;
import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;

import java.util.Collection;
import java.util.Iterator;

/**
 * TODO TGR
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public interface WorkerState<T extends TripScheduleInfo> {

    void setInitialTimeForIteration(TransferLeg accessEgressLeg, int iterationDepartureTime);

    boolean isNewRoundAvailable();

    IntIterator stopsTouchedPreviousRound();

    IntIterator stopsTouchedByTransitCurrentRound();

    void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers);

    /**
     * Extract paths after the search is complete. This method is optional,
     * returning an empty set by default.
     *
     * @return return all paths found in the search.
     */
    Collection<Path<T>> extractPaths();

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
