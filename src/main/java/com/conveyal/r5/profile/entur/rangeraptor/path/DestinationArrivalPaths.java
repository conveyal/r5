package com.conveyal.r5.profile.entur.rangeraptor.path;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparator;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSet;

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
    private final DebugHandler<DestinationArrivalView<T>> debugHandler;
    private boolean reachedCurrentRound = false;

    public DestinationArrivalPaths(
            ParetoComparator<Path<T>> paretoComparator,
            TransitCalculator calculator,
            DebugHandlerFactory<T> debugHandlerFactory,
            WorkerLifeCycle lifeCycle
    ) {
        this.paths = new ParetoSet<>(paretoComparator, debugHandlerFactory.paretoSetDebugPathListener());
        this.debugHandler = debugHandlerFactory.debugDestinationArrival();
        this.calculator = calculator;
        this.pathMapper = calculator.createPathMapper();
        lifeCycle.onPrepareForNextRound(this::clearReachedCurrentRoundFlag);
    }

    public void add(DestinationArrivalView<T> destinationArrival) {
        if (calculator.exceedsTimeLimit(destinationArrival.arrivalTime())) {
            debugRejectByTimeLimitOptimization(destinationArrival);
        } else {
            Path<T> path = pathMapper.mapToPath(destinationArrival);
            boolean added = paths.add(path);
            if (added) {
                reachedCurrentRound = true;
            }
        }
    }

    /**
     * Check if destination was reached in the current round.
     * <p/>
     * NOTE! Remember to clear flag before or after each round:
     * {@link #clearReachedCurrentRoundFlag()}.
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
        return "DestinationArrivalPaths: " + paths;
    }

    /* private methods */

    private void clearReachedCurrentRoundFlag() {
        reachedCurrentRound = false;
    }

    private void debugRejectByTimeLimitOptimization(DestinationArrivalView<T> newValue) {
        if (debugHandler != null) {
            debugHandler.reject(newValue, null, calculator.exceedsTimeLimitReason());
        }
    }

    public static <T extends TripScheduleInfo>
    ParetoComparator<Path<T>> paretoComparatorWithCost(double relaxCostAtDestinationArrival) {
        // The `travelDuration` is added as a criteria to the pareto comparator in addition to the parameters
        // used for each stop arrivals. The `travelDuration` is only needed at the destination because Range Raptor
        // works in iterations backwards in time.
        return (l, r) ->
                l.endTime() < r.endTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.totalTravelDurationInSeconds() < r.totalTravelDurationInSeconds() ||
                l.cost() < Math.round(r.cost() * relaxCostAtDestinationArrival);
    }

    public static <T extends TripScheduleInfo> ParetoComparator<Path<T>> paretoComparatorWithoutCost() {
        // The `travelDuration` is added as a criteria to the pareto comparator in addition to the parameters
        // used for each stop arrivals. The `travelDuration` is only needed at the destination because Range Raptor
        // works in iterations backwards in time.
        return (l, r) ->
                l.endTime() < r.endTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.totalTravelDurationInSeconds() < r.totalTravelDurationInSeconds();
    }
}