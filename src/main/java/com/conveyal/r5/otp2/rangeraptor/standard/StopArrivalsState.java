package com.conveyal.r5.otp2.rangeraptor.standard;

import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.standard.besttimes.BestTimes;

import java.util.Collection;

/**
 * This interface define a superset of operations to maintain the stop arrivals state, and
 * for the implementation to compute results. The Range Raptor algorithm do NOT depend on
 * the state, only on the {@link BestTimes} - with one exception the {@link #bestTimePreviousRound(int)}.
 * <p/>
 * Different implementations may implement this to:
 * <ul>
 *     <li>Compute paths
 *     <li>Enable debugging
 *     <li>Compute heuristics
 * </ul>
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
*/
public interface StopArrivalsState<T extends TripScheduleInfo> {

    void setInitialTime(final int stop, final int arrivalTime, int durationInSeconds);

    Collection<Path<T>> extractPaths();

    int bestTimePreviousRound(int stop);

    void setNewBestTransitTime(int alightStop, int alightTime, T trip, int boardStop, int boardTime, boolean newBestOverall);

    void rejectNewBestTransitTime(int alightStop, int alightTime, T trip, int boardStop, int boardTime);

    void setNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg);

    void rejectNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg);
}