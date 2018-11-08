package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransferStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;


import static java.util.Collections.emptyList;



final class Stops<T extends TripScheduleInfo> {

    private final Stop<T>[] stops;

        /**
         * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
         */
    Stops(int stops) {
        this.stops = (Stop<T>[]) new Stop[stops];
    }

    void setInitialTime(StopArrival stopArrival, int fromTime, int boardSlackInSeconds) {
        final int stop = stopArrival.stop();
        findOrCreateSet(stop).add(
                new AccessStopArrival<T>(stopArrival, fromTime, boardSlackInSeconds)
        );
    }

    boolean transitToStop(AbstractStopArrival<T> previous, int round, int stop, int time, T trip, int boardTime) {
        AbstractStopArrival<T> state = new TransitStopArrival<>(previous, round, stop, time, boardTime, trip);
        return findOrCreateSet(stop).add(state);
    }

    boolean transferToStop(AbstractStopArrival<T> previous, int round, StopArrival stopArrival, int arrivalTime) {
        return findOrCreateSet(stopArrival.stop()).add(new TransferStopArrival<>(previous, round, stopArrival, arrivalTime));
    }

    Iterable<? extends AbstractStopArrival<T>> listArrivedByTransit(int round, int stop) {
        Stop<T> it = stops[stop];
        return it == null ? emptyList() : it.list(s -> s.round() == round && s.arrivedByTransit());
    }

    Iterable<? extends AbstractStopArrival<T>> list(int round, int stop) {
        Stop it = stops[stop];
        return it == null ? emptyList() : it.listRound(round);
    }

    Iterable<? extends AbstractStopArrival<T>> listAll(int stop) {
        Stop it = stops[stop];
        return it == null ? emptyList() : it;
    }

    private Stop<T> findOrCreateSet(final int stop) {
        if(stops[stop] == null) {
            stops[stop] = createState();
        }
        return stops[stop];
    }

    static <T extends TripScheduleInfo> Stop<T> createState() {
        return new Stop<>(AbstractStopArrival.PARETO_FUNCTION);
    }

    void debugStateInfo() {
        long total = 0;
        long totalMemUsed = 0;
        long numOfStops = 0;
        int max = 0;

        for (Stop stop : stops) {
            if(stop != null) {
                ++numOfStops;
                total += stop.size();
                max = Math.max(stop.size(), max);
                totalMemUsed += stop.memUsed();
            }
        }
        double avg = ((double)total) / numOfStops;
        double avgMem = ((double)totalMemUsed) / numOfStops;

        System.out.printf(
                "%n  => Stop arrivals(McState obj): Avg: %.1f  max: %d  total: %d'  avg.mem: %.1f  tot.mem: %d'  #stops: %d'  tot#stops: %d' %n%n",
                avg, max, total/1000, avgMem, totalMemUsed/1000, numOfStops/1000, stops.length/1000
        );
    }
}
