package com.conveyal.r5.profile.entur.rangeraptor.standard.structarray;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopStateCollection;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopStateCursor;


public final class StopStatesStructArray implements StopStateCollection {

    private final StopStateStruct[][] stops;


    public StopStatesStructArray(int nRounds, int stops) {
        this.stops = new StopStateStruct[nRounds][stops];
    }

    @Override
    public void setInitialTime(int round, int stop, int time) {
        findOrCreateStopIndex(round, stop).time = time;
    }

    @Override
    public void transitToStop(int round, int stop, int time, int boardStop, int boardTime, int pattern, int trip, boolean bestTime) {
        StopStateStruct state = findOrCreateStopIndex(round, stop);

        state.arriveByTransit(time, boardStop, boardTime, pattern, trip);

        if(bestTime) {
            state.setBestTimeTransit(time);
        }
    }

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    @Override
    public void transferToStop(int round, int fromStop, StopArrival toStopArrival, int arrivalTime) {
        int stop = toStopArrival.stop();
        StopStateStruct state = findOrCreateStopIndex(round, stop);

        state.transferToStop(fromStop, arrivalTime, toStopArrival.durationInSeconds());
    }

    public final int time(int round, int stop) {
        return stops[round][stop].time;
    }

    public Cursor newCursor() {
        return new Cursor();
    }

    private StopStateStruct findOrCreateStopIndex(final int round, final int stop) {
        if(stops[round][stop] == null) {
            stops[round][stop] = new StopStateStruct();
        }
        return stops[round][stop];
    }

    public class Cursor implements StopStateCursor {

        public StopStateStruct stop(int round, int stop) {
            return stops[round][stop];
        }

        @Override
        public boolean stopNotVisited(int round, int stop) {
            return stops[round][stop] == null;
        }
    }

}
