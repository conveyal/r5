package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparator;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSet;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;


/**
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
class DestinationArrivals<T extends TripScheduleInfo> {
    /**
     * Use a BIG number as an upper bound for arrival times
     */
    private static final int UNREACHED = Integer.MAX_VALUE;

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

    private final DebugHandler<Path<T>> debugPathHandler;

    private final DebugHandler<DestinationArrivalView<T>> debugDestinationArrivalHandler;


    DestinationArrivals(int nRounds, StopsCursor<T> stopsCursor, DebugHandlerFactory<T> debugFactory) {
        this.stopsCursor = stopsCursor;
        this.bestArrivalTimesAtDestination = new int[nRounds];
        Arrays.fill(this.bestArrivalTimesAtDestination, UNREACHED);

        this.debugPathHandler = debugFactory.debugPath();
        this.debugDestinationArrivalHandler = debugFactory.debugDestinationArrival();

        paths = new ParetoSet<>(
                pathComparator(),
                debugPathHandler::drop
        );
    }

    void add(EgressStopArrivalState<T> arrival) {
        boolean added = false;
        if (newStateHaveTheBestDestinationArrivalTimeForGivenTheRound(arrival)) {
            debugDropDestinationArrival(arrival);
            int round = arrival.round();
            egressArrivalsByRound.put(round, arrival);
            bestArrivalTimesAtDestination[round] = arrival.destinationArrivalTime();
            added = true;
        }

        debugDestinationArrival(arrival, added);
    }

    void addPathsForCurrentIteration() {
        for (EgressStopArrivalState<T> it : egressArrivalsByRound.values()) {
            Path<T> path = createPathFromEgressState(it);
            boolean added = paths.add(path);
            debugPath(path, added);
        }
        egressArrivalsByRound.clear();
    }

    void setIterationDepartureTime(int departureTime) {
        debugDestinationArrivalHandler.setIterationDepartureTime(departureTime);
        debugPathHandler.setIterationDepartureTime(departureTime);
    }

    Collection<Path<T>> paths() {
        return paths;
    }


    /* Private methods */

    private boolean isDestinationReachedCurrentIteration(int round) {
        return bestArrivalTimesAtDestination[round] != UNREACHED && egressArrivalsByRound.containsKey(round);
    }

    private Path<T> createPathFromEgressState(EgressStopArrivalState<T> arrival) {
        return PathMapper.mapToPath(destinationArrivalView(arrival));
    }

    private DestinationArrivalView<T> destinationArrivalView(EgressStopArrivalState<T> arrival) {
        // Initialize the cursor to point to the current arrival

        return new StopArrivalViewAdapter.DestinationArrivalViewAdapter<T>(
                arrival.destinationDepartureTime(),
                arrival.destinationArrivalTime(),
                stopsCursor.transit(arrival.round(), arrival.stop())
        );
    }

    private boolean newStateHaveTheBestDestinationArrivalTimeForGivenTheRound(EgressStopArrivalState<T> newState) {
        return newState.destinationArrivalTime() < bestArrivalTimesAtDestination[newState.round()];
    }

    private void debugDropDestinationArrival(EgressStopArrivalState<T> arrival) {
        if (isDestinationReachedCurrentIteration(arrival.round())) {
            EgressStopArrivalState<T> dropped = egressArrivalsByRound.get(arrival.round());
            if(dropped == null) {
                // TODO TGR - Find out why this is happening?
                System.err.println("ERROR! Dropped egress stop arrival is missing: " + arrival);
                return;
            }
            if(debugDestinationArrivalHandler.isDebug(dropped.stop())) {
                debugDestinationArrivalHandler.drop(
                        destinationArrivalView(dropped),
                        destinationArrivalView(arrival)
                );
            }
        }
    }

    private void debugDestinationArrival(EgressStopArrivalState<T> arrival, boolean added) {
        if (debugDestinationArrivalHandler.isDebug(arrival.stop())) {
            if (added) {
                debugDestinationArrivalHandler.accept(destinationArrivalView(arrival), null);
            } else {
                debugDestinationArrivalHandler.reject(destinationArrivalView(arrival), null);
            }
        }
    }

    private void debugPath(Path<T> path, boolean added) {
        if (added) {
            debugPathHandler.accept(path, paths);
        } else {
            debugPathHandler.reject(path, paths);
        }
    }
}
