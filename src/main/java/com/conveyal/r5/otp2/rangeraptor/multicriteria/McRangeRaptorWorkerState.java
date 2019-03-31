package com.conveyal.r5.otp2.rangeraptor.multicriteria;

import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.transit.IntIterator;
import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.otp2.rangeraptor.WorkerState;
import com.conveyal.r5.otp2.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.otp2.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.otp2.rangeraptor.multicriteria.arrivals.TransferStopArrival;
import com.conveyal.r5.otp2.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.otp2.rangeraptor.multicriteria.heuristic.HeuristicsProvider;
import com.conveyal.r5.otp2.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.otp2.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;


/**
 * Tracks the state of a RAPTOR search, specifically the best arrival times at each transit stop at the end of a
 * particular round, along with associated data to reconstruct paths etc.
 * <p/>
 * This is grouped into a separate class (rather than just having the fields in the raptor worker class) because we
 * want the Algorithm to be as clean as possible and to be able to swap the state implementation - try out and
 * experiment with different state implementations.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final public class McRangeRaptorWorkerState<T extends TripScheduleInfo> implements WorkerState<T> {

    private final Stops<T> stops;
    private final DestinationArrivalPaths<T> paths;
    private final HeuristicsProvider<T> heuristics;
    private final List<AbstractStopArrival<T>> arrivalsCache = new ArrayList<>();
    private final CostCalculator costCalculator;
    private final TransitCalculator transitCalculator;

    /**
     * create a RaptorState for a network with a particular number of stops, and a given maximum duration
     */
    public McRangeRaptorWorkerState(
            Stops<T> stops,
            DestinationArrivalPaths<T> paths,
            HeuristicsProvider<T> heuristics,
            CostCalculator costCalculator,
            TransitCalculator transitCalculator,
            WorkerLifeCycle lifeCycle

    ) {
        this.stops = stops;
        this.paths = paths;
        this.heuristics = heuristics;
        this.costCalculator = costCalculator;
        this.transitCalculator = transitCalculator;

        // Attach to the RR life cycle
        lifeCycle.onSetupIteration((ignore) -> setupIteration());
        lifeCycle.onTransitsForRoundComplete(this::transitsForRoundComplete);
        lifeCycle.onTransfersForRoundComplete(this::transfersForRoundComplete);
    }

    /*
     The below methods are ordered after the sequence they naturally appear in the algorithm, also
     private life-cycle callbacks are listed here (not in the private method section).
    */

    // This method is private, but is part of Worker life cycle
    private void setupIteration() {
        arrivalsCache.clear();
        // clear all touched stops to avoid constant rexploration
        stops.clearTouchedStopsAndSetStopMarkers();
    }

    @Override
    public void setInitialTimeForIteration(TransferLeg accessLeg, int iterationDepartureTime) {
        addStopArrival(
                new AccessStopArrival<>(
                        accessLeg.stop(),
                        iterationDepartureTime,
                        accessLeg.durationInSeconds(),
                        costCalculator.walkCost(accessLeg.durationInSeconds()),
                        transitCalculator
                )
        );
    }

    @Override
    public boolean isNewRoundAvailable() {
        return stops.updateExist();
    }

    @Override
    public IntIterator stopsTouchedPreviousRound() {
        return stops.stopsTouchedIterator();
    }

    @Override
    public IntIterator stopsTouchedByTransitCurrentRound() {
        return stops.stopsTouchedIterator();
    }

    Iterable<? extends AbstractStopArrival<T>> listStopArrivalsPreviousRound(int stop) {
        return stops.listArrivalsAfterMarker(stop);
    }

    /**
     * Set the time at a transit stop iff it is optimal.
     */
    void transitToStop(AbstractStopArrival<T> previousStopArrival, int stop, int alightTime, int boardTime, T trip) {
        if (exceedsTimeLimit(alightTime)) {
            return;
        }
        int cost = costCalculator.transitArrivalCost(previousStopArrival.arrivalTime(), boardTime, alightTime);
        int duration = travelDuration(previousStopArrival, boardTime, alightTime);
        arrivalsCache.add(new TransitStopArrival<>(previousStopArrival, stop, alightTime, boardTime, trip, duration, cost));
    }

    /**
     * Set the time at a transit stops iff it is optimal.
     */
    @Override
    public void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers) {
        Iterable<? extends AbstractStopArrival<T>> fromArrivals = stops.listArrivalsAfterMarker(fromStop);

        while (transfers.hasNext()) {
            transferToStop(fromArrivals, transfers.next());
        }
    }

    // This method is private, but is part of Worker life cycle
    private void transitsForRoundComplete() {
        stops.clearTouchedStopsAndSetStopMarkers();
        commitCachedArrivals();
    }

    // This method is private, but is part of Worker life cycle
    private void transfersForRoundComplete() {
        commitCachedArrivals();
    }

    @Override
    public Collection<Path<T>> extractPaths() {
        stops.debugStateInfo();
        return paths.listPaths();
    }

    @Override
    public boolean isDestinationReachedInCurrentRound() {
        return paths.isReachedCurrentRound();
    }


    /* private methods */


    private void transferToStop(Iterable<? extends AbstractStopArrival<T>> fromArrivals, TransferLeg transfer) {
        final int transferTimeInSeconds = transfer.durationInSeconds();

        for (AbstractStopArrival<T> it : fromArrivals) {
            int arrivalTime = it.arrivalTime() + transferTimeInSeconds;

            if (!exceedsTimeLimit(arrivalTime)) {
                int cost = costCalculator.walkCost(transferTimeInSeconds);
                arrivalsCache.add(new TransferStopArrival<>(it, transfer, arrivalTime, cost));
            }
        }
    }

    private void commitCachedArrivals() {
        for (AbstractStopArrival<T> arrival : arrivalsCache) {
            addStopArrival(arrival);
        }
        arrivalsCache.clear();
    }

    private void addStopArrival(AbstractStopArrival<T> arrival) {
        if (heuristics.rejectDestinationArrivalBasedOnHeuristic(arrival)) {
            return;
        }
        stops.addStopArrival(arrival);
    }

    private boolean exceedsTimeLimit(int time) {
        return transitCalculator.exceedsTimeLimit(time);
    }

    private int travelDuration(AbstractStopArrival<T> prev, int boardTime, int alightTime) {
        if (prev.arrivedByAccessLeg()) {
            return transitCalculator.addBoardSlack(prev.travelDuration()) + alightTime - boardTime;
        } else {
            return prev.travelDuration() + alightTime - prev.arrivalTime();
        }
    }
}
