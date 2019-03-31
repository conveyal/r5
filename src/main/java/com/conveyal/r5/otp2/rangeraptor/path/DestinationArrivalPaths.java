package com.conveyal.r5.otp2.rangeraptor.path;

import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.ArrivalView;
import com.conveyal.r5.otp2.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.otp2.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.otp2.rangeraptor.view.DebugHandler;
import com.conveyal.r5.otp2.util.paretoset.ParetoComparator;
import com.conveyal.r5.otp2.util.paretoset.ParetoSet;

import java.util.Collection;

/**
 * The responsibility of this class is to collect result paths for destination arrivals.
 * It does so using a pareto set. The comparator is passed in as an argument to the
 * constructor. This make is possible to collect different sets in different scenarios.
 * <p/>
 * Depending on the pareto comparator passed into the constructor this class grantee that the
 * best paths with respect to <em>arrival time</em>, <em>rounds</em> and <em>travel duration</em>
 * are found. You may also add <em>cost</em> as a criteria (multi-criteria search).
 * <p/>
 * This class is a thin wrapper around a ParetoSet of {@link Path}s. Before paths are added the
 * arrival time is checked against the arrival time limit.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class DestinationArrivalPaths<T extends TripScheduleInfo> {
    private final ParetoSet<Path<T>> paths;
    private final TransitCalculator calculator;
    private final PathMapper<T> pathMapper;
    private final DebugHandler<ArrivalView<T>> debugHandler;
    private boolean reachedCurrentRound = false;

    public DestinationArrivalPaths(
            ParetoComparator<Path<T>> paretoComparator,
            TransitCalculator calculator,
            DebugHandlerFactory<T> debugHandlerFactory,
            WorkerLifeCycle lifeCycle
    ) {
        this.paths = new ParetoSet<>(paretoComparator, debugHandlerFactory.paretoSetDebugPathListener());
        this.debugHandler = debugHandlerFactory.debugStopArrival();
        this.calculator = calculator;
        this.pathMapper = calculator.createPathMapper();
        lifeCycle.onPrepareForNextRound(this::clearReachedCurrentRoundFlag);
    }

    public void add(ArrivalView<T> egressStopArrival, TransferLeg egressLeg, int additionalCost) {
        int arrivalTime = calculator.plusDuration(egressStopArrival.arrivalTime(), egressLeg.durationInSeconds());
        DestinationArrival<T> destArrival = new DestinationArrival<>(egressStopArrival, arrivalTime, additionalCost);

        if (calculator.exceedsTimeLimit(arrivalTime)) {
            debugRejectByTimeLimitOptimization(destArrival);
        } else {
            Path<T> path = pathMapper.mapToPath(destArrival);
            boolean added = paths.add(path);
            if (added) {
                reachedCurrentRound = true;
            }
        }
    }

    /**
     * Check if destination was reached in the current round.
     */
    public boolean isReachedCurrentRound() {
        return reachedCurrentRound;
    }

    public boolean isEmpty() {
        return paths.isEmpty();
    }

    public boolean qualify(int departureTime, int arrivalTime, int numberOfTransfers, int cost) {
        return paths.qualify(Path.dummyPath(departureTime, arrivalTime, numberOfTransfers, cost));
    }

    public Collection<Path<T>> listPaths() {
        return paths;
    }

    @Override
    public String toString() {
        return paths.toString();
    }


    /* private methods */

    private void clearReachedCurrentRoundFlag() {
        reachedCurrentRound = false;
    }

    private void debugRejectByTimeLimitOptimization(DestinationArrival<T> destArrival) {
        if (debugHandler != null) {
            debugHandler.reject(destArrival.previous(), null, calculator.exceedsTimeLimitReason());
        }
    }
}