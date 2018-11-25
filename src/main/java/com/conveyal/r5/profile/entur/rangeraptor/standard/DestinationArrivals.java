package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparatorBuilder;
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
 */
class DestinationArrivals<T extends TripScheduleInfo> {
    /** Use a BIG number as an upper bound for arrival times */
    private static final int UNREACHED = Integer.MAX_VALUE;

    /** Best arrival times at DESTINATION for each round (across all iterations) */
    private final int[] bestArrivalTimesAtDestination;

    /** The best egress arrivals found in last iteration per round */
    private final Map<Integer, EgressStopArrivalState<T>> egressArrivalsByRound = new HashMap<>();

    /** Stop state cursor used to access the stop arrivals to construct paths. */
    private final StopsCursor<T> stopsCursor;

    /**
     * Keep only paths that are pareto-optimal with the given criteria:
     * <ul>
     * <li> Lowest {@code endTime}
     * <li> Lowest {@code numberOfTransfers}
     * <li> Lowest {@code totalTravelDurationInSeconds}
     * </ul>
     * The pareto set filter away suboptimal paths.
     */
    private final Collection<Path<T>> paths = new ParetoSet<>(
            new ParetoComparatorBuilder<Path<T>>()
                    .lessThen(Path::endTime)
                    .lessThen(Path::numberOfTransfers)
                    .lessThen(Path::totalTravelDurationInSeconds)
                    .build()
    );


    DestinationArrivals(int nRounds, StopsCursor<T> stopsCursor) {
        this.stopsCursor = stopsCursor;
        this.bestArrivalTimesAtDestination = new int[nRounds];
        Arrays.fill(this.bestArrivalTimesAtDestination, UNREACHED);
    }

    void add(EgressStopArrivalState<T> state) {
        if (newStateHaveTheBestDestinationArrivalTimeForGivenTheRound(state)) {
            int round = state.round();
            egressArrivalsByRound.put(round, state);
            bestArrivalTimesAtDestination[round] = state.destinationArrivalTime();
        }
    }

    void addPathsForCurrentIteration() {
        for (EgressStopArrivalState<T> it : egressArrivalsByRound.values()) {
            paths.add(createPathFromEgressState(it));
        }
        egressArrivalsByRound.clear();
    }

    Collection<Path<T>> paths() {
        return paths;
    }


    /* Private methods */

    private Path<T> createPathFromEgressState(EgressStopArrivalState<T> state) {
        // Initialize the cursor to point to the current arrival
        stopsCursor.transit(state.round(), state.stop());
        // Use the cursor and the PathMapper to create a new path
        return PathMapper.mapToPath(
                new StopArrivalViewAdapter.DestinationArrivalViewAdapter<>(
                        state.destinationDepartureTime(),
                        state.destinationArrivalTime(),
                        stopsCursor::current
                )
        );
    }

    private boolean newStateHaveTheBestDestinationArrivalTimeForGivenTheRound(EgressStopArrivalState<T> newState) {
        return newState.destinationArrivalTime() < bestArrivalTimesAtDestination[newState.round()];
    }
}
