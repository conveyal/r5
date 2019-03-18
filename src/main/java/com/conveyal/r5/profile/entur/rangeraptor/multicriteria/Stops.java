package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.debug.DebugLogger;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.api.view.Heuristics;
import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic.HeuristicsProvider;
import com.conveyal.r5.profile.entur.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.profile.entur.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;

import java.util.Collection;
import java.util.stream.Collectors;

import static com.conveyal.r5.profile.entur.api.debug.DebugTopic.SEARCH_STATS;
import static java.util.Collections.emptyList;


/**
 * This class serve as a thin wrapper around the stops array and the destination arrivals.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class Stops<T extends TripScheduleInfo> {
    private final StopArrivalParetoSet<T>[] stops;
    private final HeuristicsProvider<T> heuristics;
    private final DestinationArrivalPaths<T> paths;
    private final DebugHandlerFactory<T> debugHandlerFactory;
    private final TransitCalculator calculator;
    private final DebugLogger debugLogger;

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    Stops(
            int nStops,
            Collection<TransferLeg> egressLegs,
            double relaxCostAtDestinationArrival,
            RoundProvider roundProvider,
            Heuristics heuristics,
            CostCalculator costCalculator,
            TransitCalculator transitCalculator,
            DebugHandlerFactory<T> debugHandlerFactory,
            DebugLogger debugLogger,
            WorkerLifeCycle lifeCycle
    ) {
        //noinspection unchecked
        this.stops = (StopArrivalParetoSet<T>[]) new StopArrivalParetoSet[nStops];
        this.calculator = transitCalculator;
        this.debugHandlerFactory = debugHandlerFactory;
        this.debugLogger = debugLogger;
        this.paths = new DestinationArrivalPaths<>(
                DestinationArrivalPaths.paretoComparatorWithCost(relaxCostAtDestinationArrival),
                calculator,
                debugHandlerFactory,
                lifeCycle
        );
        this.heuristics = heuristics == null ? null : new HeuristicsProvider<>(heuristics, roundProvider, paths, costCalculator);

        for (TransferLeg it : egressLegs) {
            glueTogetherEgressStopWithDestinationArrivals(it, costCalculator);
        }
    }

    void setInitialTime(int fromTime, TransferLeg accessLeg, int cost) {
        AccessStopArrival<T> newAccessArrival = new AccessStopArrival<>(
                accessLeg.stop(),
                fromTime,
                accessLeg.durationInSeconds(),
                cost,
                calculator
        );
        addStopArrival(newAccessArrival);
    }

    /**
     * Delegates to {@link DestinationArrivalPaths#isReachedCurrentRound()}
     */
    boolean isDestinationReachedInCurrentRound() {
        return paths.isReachedCurrentRound();
    }

    boolean addStopArrival(AbstractStopArrival<T> arrival) {
        if(rejectDestinationArrivalBasedOnHeuristic(arrival)) {
            rejectByOptimization(arrival);
            return false;
        }
        return findOrCreateSet(arrival.stop()).add(arrival);
    }

    Collection<Path<T>> extractPaths() {
        debugStateInfo();
        return paths.listPaths();
    }

    /** List all transits arrived this round. */
    Iterable<AbstractStopArrival<T>> listArrivalsAfterMark(final int stop) {
        StopArrivalParetoSet<T> it = stops[stop];
        if(it==null) {
            return emptyList();
        }
        return it.streamAfterMarker().collect(Collectors.toList());
    }

    void markAllStops() {
        for (StopArrivalParetoSet<T> stop : stops) {
            if (stop != null) {
                stop.markAtEndOfSet();
            }
        }
    }

    private StopArrivalParetoSet<T> findOrCreateSet(final int stop) {
        if(stops[stop] == null) {
            stops[stop] = StopArrivalParetoSet.createStopArrivalSet(stop, debugHandlerFactory);
        }
        return stops[stop];
    }

    /**
     * This method creates a ParetoSet for the given egress stop. When arrivals are added to the
     * stop, the "glue" make sure new destination arrivals is added to the destination arrivals.
     */
    private void glueTogetherEgressStopWithDestinationArrivals(
            TransferLeg egressLeg,
            CostCalculator costCalculator
    ) {
        int stop = egressLeg.stop();
        // The factory is creating the actual "glue"
        this.stops[stop] = StopArrivalParetoSet.createEgressStopArrivalSet(
                egressLeg,
                costCalculator,
                paths,
                debugHandlerFactory
        );
    }

    private boolean rejectDestinationArrivalBasedOnHeuristic(AbstractStopArrival<T> arrival) {
        if(heuristics == null || paths.isEmpty()) {
            return false;
        }
        return !heuristics.qualify(arrival.stop(), arrival.arrivalTime(), arrival.travelDuration(), arrival.cost());
    }

    private void rejectByOptimization(AbstractStopArrival<T> arrival) {
        if (debugHandlerFactory.isDebugStopArrival(arrival.stop())) {
            String details = heuristics.rejectErrorMessage(arrival.stop()) +
                    ", Existing paths: " + paths;

            debugHandlerFactory.debugStopArrival().reject(
                    arrival,
                    null,
                    "The element is rejected because the destination is not reachable within the limit " +
                            "based on heuristic. Details: " + details
            );
        }
    }

    private void debugStateInfo() {
        if(!debugLogger.isEnabled(SEARCH_STATS)) return;

        long total = 0;
        long arrayLen = 0;
        long numOfStops = 0;
        int max = 0;

        for (StopArrivalParetoSet stop : stops) {
            if(stop != null) {
                ++numOfStops;
                total += stop.size();
                max = Math.max(stop.size(), max);
                arrayLen += stop.internalArrayLength();
            }
        }
        double avg = ((double)total) / numOfStops;
        double arrayLenAvg = ((double)arrayLen) / numOfStops;

        debugLogger.debug(
                SEARCH_STATS,
                " => STOP ARRIVALS  %.1f / %d / %d'  Array Length: %.1f / %d'  Stops: %d' / %d'%n",
                avg, max, total/1000, arrayLenAvg, arrayLen/1000, numOfStops/1000, stops.length/1000
        );
    }
}
