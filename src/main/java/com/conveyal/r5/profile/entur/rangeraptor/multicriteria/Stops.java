package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.DestinationArrival;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;
import com.conveyal.r5.profile.entur.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.util.Debug;

import java.util.Collection;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;


/**
 * This class serve as a thin wrapper around the stops array and the destination arrivals.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class Stops<T extends TripScheduleInfo> {
    private final StopArrivalParetoSet<T>[] stops;
    private final DestinationHeuristic[] heuristics;
    private final DestinationArrivals<T> destinationArrivals;
    private final DebugHandlerFactory<T> debugHandlerFactory;
    private final PathMapper<T> pathMapper;
    private final TransitCalculator calculator;

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    Stops(
            int nStops,
            Collection<TransferLeg> egressLegs,
            double relaxCostAtDestinationArrival,
            DestinationHeuristic[] heuristics,
            CostCalculator costCalculator,
            TransitCalculator transitCalculator,
            DebugHandlerFactory<T> debugHandlerFactory
    ) {
        //noinspection unchecked
        this.stops = (StopArrivalParetoSet<T>[]) new StopArrivalParetoSet[nStops];
        this.heuristics = heuristics;
        this.calculator = transitCalculator;
        this.pathMapper = calculator.createPathMapper();
        this.debugHandlerFactory = debugHandlerFactory;
        this.destinationArrivals = new DestinationArrivals<>(
                relaxCostAtDestinationArrival,
                calculator,
                debugHandlerFactory
        );
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
     * Delegates to {@link DestinationArrivals#reachedCurrentRound()}
     */
    boolean isDestinationReachedInCurrentRound() {
        return destinationArrivals.reachedCurrentRound();
    }

    /**
     * Delegates to {@link DestinationArrivals#clearReachedCurrentRoundFlag()}
     */
    void clearReachedCurrentRoundFlag() {
        destinationArrivals.clearReachedCurrentRoundFlag();
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
        return destinationArrivals.mapToList(pathMapper::mapToPath);
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
                destinationArrivals,
                debugHandlerFactory
        );
    }

    private boolean rejectDestinationArrivalBasedOnHeuristic(AbstractStopArrival<T> arrival) {
        if(heuristics == null || destinationArrivals.isEmpty()) {
            return false;
        }
        DestinationHeuristic heuristic = heuristics[arrival.stop()];

        if(heuristic == null) {
            return true;
        }
        return !destinationArrivals.qualify(new DestinationArrival<>(arrival, heuristic));
    }

    private void rejectByOptimization(AbstractStopArrival<T> arrival) {
        if (debugHandlerFactory.isDebugStopArrival(arrival.stop())) {
            String details = heuristics[arrival.stop()] == null
                    ? "The stop was not reached in the heuristic calculation."
                    : heuristics[arrival.stop()].toString();

            if(!destinationArrivals.isEmpty()) {
                details += " " + destinationArrivals;
            }

            debugHandlerFactory.debugStopArrival().reject(
                    arrival,
                    null,
                    "The element is rejected because the destination is not reachable within the limit " +
                            "based on heuristic. Details: " + details
            );
        }
    }

    private void debugStateInfo() {
        if(!Debug.isDebug()) return;

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

        System.err.printf(
                " => STOP ARRIVALS  %.1f / %d / %d'  Array Length: %.1f / %d'  Stops: %d' / %d'%n",
                avg, max, total/1000, arrayLenAvg, arrayLen/1000, numOfStops/1000, stops.length/1000
        );
    }
}
