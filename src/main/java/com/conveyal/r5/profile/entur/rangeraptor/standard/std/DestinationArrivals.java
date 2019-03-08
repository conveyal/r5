package com.conveyal.r5.profile.entur.rangeraptor.standard.std;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;
import com.conveyal.r5.profile.entur.rangeraptor.standard.ArrivedAtDestinationCheck;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.view.StopsCursor;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparator;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSet;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;


/**
 * TODO TGR - This class can probebly be merged with the class in the multicriteria package.
 * TODO TGR - Debuggins should be extracted out of this class.
 *
 * The responsibility of this class is to collect all result paths for a given destination.
 * <p/>
 * Range Raptor requires paths to be collected at the end of each iteration. Following
 * iterations may overwrite the existing state; Hence invalidate trips explored in previous
 * iterations. Also, you should not collect paths before an iteration is complete, because
 * there might be better solution - arriving at the destination before the current one found.
 * <p/>
 * This class grantee that the best paths with respect to <em>arrival time</em> and <em>rounds</em>
 * are found. It also grantee that the shortest path (travel duration) is found within the search
 * window, but there might be a shorter travel duration for a path that depart outside the search
 * time window.
 * <p/>
 * The class achieve its goal by:
 * <ol>
 * <li>keeping a list of the best <em>arrival times</em> at the destination for each round.
 * <li>keeping egress arrivals until each iteration is done for each <em>arrival time</em>
 * found in the last Range Raptor iteration.
 * <li>converting egress states to paths after each iteration.
 * </ol>
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class DestinationArrivals<T extends TripScheduleInfo> implements ArrivedAtDestinationCheck {
    private static <T extends TripScheduleInfo> ParetoComparator<Path<T>> pathComparator() {
        return (l, r) ->
                l.endTime() < r.endTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.totalTravelDurationInSeconds() < r.totalTravelDurationInSeconds();
    }

    /**
     * Best arrival times at DESTINATION for each round (across all iterations)
     */
    private final int[] bestArrivalTimesAtDestination;

    /**
     * The best egress arrivals found in last iteration per round
     */
    private final Map<Integer, EgressStopArrivalState<T>> egressArrivalsByRound = new HashMap<>();

    /**
     * Stop state cursor used to access the stop arrivals to construct paths.
     */
    private final StopsCursor<T> stopsCursor;

    /**
     * Keep only paths that are pareto-optimal with the given criteria.
     * <p/>
     * The pareto set filter away suboptimal paths.
     */
    private final Collection<Path<T>> paths;

    private final TransitCalculator calculator;

    private final PathMapper<T> pathMapper;

    private final DebugHandler<DestinationArrivalView<T>> debugDestinationArrivalHandler;

    private boolean arrivedAtDestinationInLastRound = false;


    public DestinationArrivals(
            int nRounds,
            TransitCalculator calculator,
            StopsCursor<T> stopsCursor,
            DebugHandlerFactory<T> debugFactory,
            WorkerLifeCycle lifeCycle
    ) {
        this.stopsCursor = stopsCursor;
        this.calculator = calculator;
        this.bestArrivalTimesAtDestination = new int[nRounds];
        Arrays.fill(this.bestArrivalTimesAtDestination, calculator.latestAcceptableArrivalTime());

        this.debugDestinationArrivalHandler = debugFactory.debugDestinationArrival();

        this.pathMapper = calculator.createPathMapper();
        this.paths = new ParetoSet<>(pathComparator(), debugFactory.paretoSetDebugPathListener());

        // Attatch to Worker life cycle
        lifeCycle.onIterationComplete(this::addPathsForCurrentIteration);
        lifeCycle.onPrepareForNextRound(this::prepareForNextRound);
    }

    public void add(EgressStopArrivalState<T> arrival) {
        if (newStateHaveTheBestDestinationArrivalTimeForGivenTheRound(arrival)) {
            debugDropDestinationArrival(arrival);
            int round = arrival.round();
            egressArrivalsByRound.put(round, arrival);
            bestArrivalTimesAtDestination[round] = destinationArrivalTime(arrival);
            arrivedAtDestinationInLastRound = true;
            debugAcceptArrival(arrival);
        } else {
            debugRejectArrival(arrival);
        }
    }

    Collection<Path<T>> paths() {
        return paths;
    }

    @Override
    public boolean arrivedAtDestinationCurrentRound() {
        return arrivedAtDestinationInLastRound;
    }


    /* Private methods */

    private void prepareForNextRound() {
        arrivedAtDestinationInLastRound = false;
    }

    /**
     * Create paths at the end of each iteration. This is necessary to avoid stop arrivals
     * part of a path found in the current itteration to be overwritten by a new
     * stop arrival found in a later iteration.
     */
    private void addPathsForCurrentIteration() {
        for (EgressStopArrivalState<T> it : egressArrivalsByRound.values()) {
            paths.add(createPathFromEgressState(it));
        }
        egressArrivalsByRound.clear();
    }


    private int destinationArrivalTime(EgressStopArrivalState<T> arrival) {
        return calculator.add(arrival.transitTime(), arrival.egressLeg().durationInSeconds());
    }

    private Path<T> createPathFromEgressState(EgressStopArrivalState<T> arrival) {
        return pathMapper.mapToPath(destinationArrivalView(arrival));
    }

    private DestinationArrivalView<T> destinationArrivalView(EgressStopArrivalState<T> arrival) {
        // Initialize the cursor to point to the current arrival
        return stopsCursor.destinationArrival(arrival);
    }

    private boolean newStateHaveTheBestDestinationArrivalTimeForGivenTheRound(EgressStopArrivalState<T> newState) {
        return calculator.isBest(destinationArrivalTime(newState), bestArrivalTimesAtDestination[newState.round()]);
    }

    private void debugDropDestinationArrival(EgressStopArrivalState<T> arrival) {
        EgressStopArrivalState<T> dropped = egressArrivalsByRound.get(arrival.round());
        if (dropped != null && isDebugArrival(dropped.stop())) {
            debugDestinationArrivalHandler.drop(
                    destinationArrivalView(dropped),
                    destinationArrivalView(arrival)
            );
        }
    }

    private void debugAcceptArrival(EgressStopArrivalState<T> arrival) {
        if (isDebugArrival(arrival.stop())) {
            debugDestinationArrivalHandler.accept(destinationArrivalView(arrival), null);
        }
    }

    private void debugRejectArrival(EgressStopArrivalState<T> arrival) {
        if (isDebugArrival(arrival.stop())) {
            debugDestinationArrivalHandler.reject(destinationArrivalView(arrival), null);
        }
    }

    private boolean isDebugArrival(int stop) {
        return debugDestinationArrivalHandler != null && debugDestinationArrivalHandler.isDebug(stop);
    }
}
