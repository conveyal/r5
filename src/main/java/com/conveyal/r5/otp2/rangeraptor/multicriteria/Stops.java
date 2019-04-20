package com.conveyal.r5.otp2.rangeraptor.multicriteria;


import com.conveyal.r5.otp2.api.debug.DebugLogger;
import com.conveyal.r5.otp2.api.transit.IntIterator;
import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.otp2.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.otp2.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.otp2.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.otp2.util.BitSetIterator;

import java.util.BitSet;
import java.util.Collection;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;


/**
 * This class serve as a wrapper for all stop arrival pareto set, one set for each stop.
 * It also keep track of stops visited since "last mark".
 * <p>
 *
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class Stops<T extends TripScheduleInfo> {
    private final StopArrivalParetoSet<T>[] stops;
    private final BitSet touchedStops;
    private final DebugHandlerFactory<T> debugHandlerFactory;
    private final DebugStopArrivalsStatistics debugStats;

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    public Stops(
            int nStops,
            Collection<TransferLeg> egressLegs,
            DestinationArrivalPaths<T> paths,
            CostCalculator costCalculator,
            DebugHandlerFactory<T> debugHandlerFactory,
            DebugLogger debugLogger
    ) {
        //noinspection unchecked
        this.stops = (StopArrivalParetoSet<T>[]) new StopArrivalParetoSet[nStops];
        this.touchedStops = new BitSet(nStops);
        this.debugHandlerFactory = debugHandlerFactory;
        this.debugStats = new DebugStopArrivalsStatistics(debugLogger);

        for (TransferLeg it : egressLegs) {
            glueTogetherEgressStopWithDestinationArrivals(it, costCalculator, paths);
        }
    }

    boolean updateExist() {
        return !touchedStops.isEmpty();
    }

    IntIterator stopsTouchedIterator() {
        return new BitSetIterator(touchedStops);
    }

    void addStopArrival(AbstractStopArrival<T> arrival) {
        boolean added = findOrCreateSet(arrival.stop()).add(arrival);
        if (added) {
            touchedStops.set(arrival.stop());
        }
    }

    void debugStateInfo() {
        debugStats.debugStatInfo(stops);
    }

    /** List all transits arrived this round. */
    Iterable<AbstractStopArrival<T>> listArrivalsAfterMarker(final int stop) {
        StopArrivalParetoSet<T> it = stops[stop];
        if(it==null) {
            return emptyList();
        }
        return it.streamAfterMarker().collect(Collectors.toList());
    }

    void clearTouchedStopsAndSetStopMarkers() {
        IntIterator it = stopsTouchedIterator();
        while (it.hasNext()) {
            stops[it.next()].markAtEndOfSet();
        }
        touchedStops.clear();
    }


    /* private methods */

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
            CostCalculator costCalculator,
            DestinationArrivalPaths<T> paths
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
}
