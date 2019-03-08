package com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalsState;

import java.util.Collection;
import java.util.Collections;

/**
 * The responsibility of this class is to calculate the best arrival times at every stop.
 * This class do NOT keep track of results paths.
 * <p/>
 * The {@link #bestTimePreviousRound(int)} return an estimate of the best time for the
 * previous round by using the overall best time (any round including the current round).
 * <p/>
 *
 * TODO TGR - Turn this into a class to calculate best times and optimistic number of hops.
 * TODO TGR - Investigate if it is possible to include transfers results in the forward search,
 * TODO TGR - but not in the reverse search. This feature is usful when combining a FORWARD
 * TODO TGR - and REVERSE search to compute a pruning stop bit set. This is most likely not
 * TODO TGR - feasable for the overall best times, but ok for the calculation of hops/transfers.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class BestTimesOnlyStopArrivalsState<T extends TripScheduleInfo> implements StopArrivalsState<T> {

    private final BestTimes bestTimes;

    public BestTimesOnlyStopArrivalsState(BestTimes bestTimes) {
        this.bestTimes = bestTimes;
    }

    @Override
    public void setInitialTime(int stop, int arrivalTime, int durationInSeconds) { }

    @Override
    public Collection<Path<T>> extractPaths() { return Collections.emptyList(); }

    /**
     * This implementation does NOT return the "best time in the previous round"; It returns the
     * overall "best time" across all rounds including the current.
     * <p/>
     * This is a simplification, *bestTimes* might get updated during the current round; Hence
     * leading to a new boarding at the alight stop in the same round. If we do not count rounds
     * or track paths, this is OK.
     * <P/>
     * Because this rarely happens and heuristics does not need to be exact - it only need to be
     * optimistic. So if we arrive at a stop one or two rounds to early, the only effect is that
     * the "number of transfers" for those stops is to small - or what we call a optimistic estimate.
     * <p/>
     * The "arrival time" is calculated correctly.
     */
    @Override
    public int bestTimePreviousRound(int stop) { return bestTimes.time(stop); }

    @Override
    public void setNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime, boolean newBestOverall) { }

    @Override
    public void rejectNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime) { }

    @Override
    public void setNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) { }

    @Override
    public void rejectNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) { }
}
