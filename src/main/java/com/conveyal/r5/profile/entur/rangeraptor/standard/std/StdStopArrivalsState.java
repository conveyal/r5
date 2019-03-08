package com.conveyal.r5.profile.entur.rangeraptor.standard.std;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalsState;

import java.util.Collection;


/**
 * Tracks the state necessary to construct paths at the end of each iteration.
 * <p/>
 * This class find the pareto optimal paths with respect to: rounds, arrival time and total travel time.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class StdStopArrivalsState<T extends TripScheduleInfo> implements StopArrivalsState<T> {

    private final Stops<T> stops;
    private final DestinationArrivals<T> results;

    /**
     * Create a Standard Range Raptor state for the given stops and destination arrivals.
     */
    public StdStopArrivalsState(Stops<T> stops, DestinationArrivals<T> destinationArrivals) {
        this.stops = stops;
        this.results = destinationArrivals;
    }

    @Override
    public final void setInitialTime(final int stop, final int arrivalTime, int durationInSeconds) {
        stops.setInitialTime(stop, arrivalTime, durationInSeconds);
    }

    @Override
    public final Collection<Path<T>> extractPaths() {
        return results.paths();
    }

    @Override
    public final int bestTimePreviousRound(int stop) {
        return stops.bestTimePreviousRound(stop);
    }


    @Override
    public void setNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime, boolean newBestOverall) {
        stops.transitToStop(stop, alightTime, boardStop, boardTime, trip, newBestOverall);
    }

    @Override
    public void rejectNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime) {
    }

    @Override
    public void setNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
        stops.transferToStop(fromStop, transferLeg, arrivalTime);
    }

    @Override
    public void rejectNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
    }
}