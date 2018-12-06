package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.AccessLeg;
import com.conveyal.r5.profile.entur.api.transit.EgressLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;
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
    private final Stop<T>[] stops;
    private final Destination<T> destination = new Destination<>();
    private final TransitCalculator calculator;

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    Stops(int nStops, Collection<EgressLeg> egressLegs, TransitCalculator calculator) {
        //noinspection unchecked
        this.stops = (Stop<T>[]) new Stop[nStops];
        this.calculator = calculator;

        for (EgressLeg it : egressLegs) {
            this.stops[it.stop()] = new EgressStop<>(it, destination);
        }
    }

    void setInitialTime(AccessLeg accessLeg, int fromTime) {
        addStopArrival(
                new AccessStopArrival<>(
                        accessLeg.stop(),
                        fromTime,
                        accessLeg.durationInSeconds(),
                        accessLeg.cost(),
                        calculator
                )
        );
    }

    boolean addStopArrival(AbstractStopArrival<T> arrival) {
        return findOrCreateSet(arrival.stop()).add(arrival);
    }

    Collection<Path<T>> extractPaths() {
        debugStateInfo();
        return destination.stream().map(PathMapper::mapToPath).collect(Collectors.toList());
    }

    /** List all transits arrived this round. */
    Iterable<AbstractStopArrival<T>> listArrivalsAfterMark(final int stop) {
        Stop<T> it = stops[stop];
        if(it==null) {
            return emptyList();
        }
        return it.streamAfterMarker().collect(Collectors.toList());
    }

    Iterable<? extends AbstractStopArrival<T>> list(final int round, final int stop) {
        Stop<T> it = stops[stop];
        if(it==null) {
            return emptyList();
        }
        return it.list(s -> s.round() == round);
    }

    void markAllStops() {
        for (Stop<T> stop : stops) {
            if (stop != null) {
                stop.markAtEndOfSet();
            }
        }
    }

    private Stop<T> findOrCreateSet(final int stop) {
        if(stops[stop] == null) {
            stops[stop] = new Stop<>();
        }
        return stops[stop];
    }

    private void debugStateInfo() {
        if(!Debug.isDebug()) return;

        long total = 0;
        long arrayLen = 0;
        long numOfStops = 0;
        int max = 0;

        for (Stop stop : stops) {
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
