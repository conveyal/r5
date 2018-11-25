package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.AccessLeg;
import com.conveyal.r5.profile.entur.api.EgressLeg;
import com.conveyal.r5.profile.entur.api.TransferLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransferStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.util.Debug;

import java.util.Collection;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;


/**
 * This class serve as a thin wrapper around the stops array and the destination arrivals.
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
        final int stop = accessLeg.stop();
        findOrCreateSet(stop).add(
                new AccessStopArrival<>(
                        accessLeg.stop(),
                        fromTime,
                        accessLeg.durationInSeconds(),
                        accessLeg.cost(),
                        calculator
                )
        );
    }

    boolean transitToStop(AbstractStopArrival<T> previous, int round, int stop, int time, T trip, int boardTime) {
        AbstractStopArrival<T> state = new TransitStopArrival<>(previous, round, stop, time, boardTime, trip);
        return findOrCreateSet(stop).add(state);
    }

    boolean transferToStop(AbstractStopArrival<T> previous, int round, TransferLeg transferLeg, int arrivalTime) {
        return findOrCreateSet(transferLeg.stop()).add(new TransferStopArrival<>(previous, round, transferLeg, arrivalTime));
    }

    Collection<Path<T>> extractPaths() {
        debugStateInfo();
        return destination.stream().map(PathMapper::mapToPath).collect(Collectors.toList());
    }

    /**
     * List all transits arrived last round.
     * <p/>
     * <b>NOTE! This method can only be called once per round, after the call the flags are cleared automatically.</b>
     */
    Iterable<? extends AbstractStopArrival<T>> listArrivedByTransitLastRound(int stop) {
        Stop<T> it = stops[stop];
        return it == null ? emptyList() : it.list(AbstractStopArrival::arrivedByTransitLastRound);
    }

    Iterable<? extends AbstractStopArrival<T>> list(final int round, int stop) {
        Stop<T> it = stops[stop];
        return it == null ? emptyList() : it.list(s -> s.round() == round);
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
                arrayLen += stop.elementArrayLen();
            }
        }
        double avg = ((double)total) / numOfStops;
        double arrayLenAvg = ((double)arrayLen) / numOfStops;

        System.out.printf(
                " => STOP ARRIVALS  %.1f / %d / %d'  Array Length: %.1f / %d'  Stops: %d' / %d'%n",
                avg, max, total/1000, arrayLenAvg, arrayLen/1000, numOfStops/1000, stops.length/1000
        );
    }
}
