package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopState;


import static java.util.Collections.emptyList;



final class StopStates {

    private final StopStateParetoSet[] stops;

        /**
         * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
         */
    StopStates(int stops) {
        this.stops = new StopStateParetoSet[stops];
    }

    void setInitialTime(StopArrival stopArrival, int fromTime, int boardSlackInSeconds) {
        final int stop = stopArrival.stop();
        findOrCreateSet(stop).add(
                new McAccessStopState(stopArrival, fromTime, boardSlackInSeconds)
        );
    }

    boolean transitToStop(StopState previous, int round, int stop, int time, int pattern, int trip, int boardTime) {
        McStopState state = new McTransitStopState((McStopState) previous, round, stop, time, boardTime, pattern, trip);
        return findOrCreateSet(stop).add(state);
    }

    boolean transferToStop(McStopState previous, int round, StopArrival stopArrival, int arrivalTime) {
        return findOrCreateSet(stopArrival.stop()).add(new McTransferStopState(previous, round, stopArrival, arrivalTime));
    }

    Iterable<? extends McStopState> listArrivedByTransit(int round, int stop) {
        StopStateParetoSet it = stops[stop];
        return it == null ? emptyList() : it.list(s -> s.round() == round && s.arrivedByTransit());
    }

    Iterable<? extends McStopState> list(int round, int stop) {
        StopStateParetoSet it = stops[stop];
        return it == null ? emptyList() : it.listRound(round);
    }

    Iterable<? extends McStopState> listAll(int stop) {
        StopStateParetoSet it = stops[stop];
        return it == null ? emptyList() : it;
    }

    private StopStateParetoSet findOrCreateSet(final int stop) {
        if(stops[stop] == null) {
            stops[stop] = createState();
        }
        return stops[stop];
    }

    static StopStateParetoSet createState() {
        return new StopStateParetoSet(McStopState.PARETO_FUNCTION);
    }

    void debugStateInfo() {
        long total = 0;
        long totalMemUsed = 0;
        long numOfStops = 0;
        int max = 0;

        for (StopStateParetoSet stop : stops) {
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
