package com.conveyal.r5.profile.mcrr.mc;


import com.conveyal.r5.profile.mcrr.StopState;
import com.conveyal.r5.profile.mcrr.util.ParetoDominanceFunctions;

import static com.conveyal.r5.profile.mcrr.util.ParetoDominanceFunctions.createParetoDominanceFunctionArray;
import static java.util.Collections.emptyList;



final class StopStatesParetoSet  {
    private static final ParetoDominanceFunctions.Builder PARETO_FUNCTION = createParetoDominanceFunctionArray()
            .lessThen() // rounds
            .lessThen(1) // time - needs to be 1 seconds better to make it into the pareto set.
    ;


    private final StopStateParetoSet[] stops;

        /**
         * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
         */
    StopStatesParetoSet(int stops) {
        this.stops = new StopStateParetoSet[stops];
    }

    void setInitialTime(int stop, int time) {
        findOrCreateSet(stop).add(new McAccessStopState(stop, time));
    }

    boolean transitToStop(StopState previous, int round, int stop, int time, int pattern, int trip, int boardTime) {
        McStopState state = new McTransitStopState((McStopState) previous, round, stop, time, boardTime, pattern, trip);
        return findOrCreateSet(stop).add(state);
    }

    boolean transferToStop(McStopState previous, int round, int targetStop, int time, int transferTime) {
        return findOrCreateSet(targetStop).add(new McTransferStopState(previous, round, targetStop, time, transferTime));
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
        return it == null ? emptyList() : it.paretoSet();
    }

    private StopStateParetoSet findOrCreateSet(final int stop) {
        if(stops[stop] == null) {
            stops[stop] = createState();
        }
        return stops[stop];
    }

    static StopStateParetoSet createState() {
        return new StopStateParetoSet(PARETO_FUNCTION);
    }
}
