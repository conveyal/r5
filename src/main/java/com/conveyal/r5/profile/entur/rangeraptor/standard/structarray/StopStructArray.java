package com.conveyal.r5.profile.entur.rangeraptor.standard.structarray;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalCollection;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalCursor;


public final class StopStructArray<T extends TripScheduleInfo> implements StopArrivalCollection<T> {

    private final StructStopArrival<T>[][] stops;


    public StopStructArray(int nRounds, int stops) {
        //noinspection unchecked
        this.stops = (StructStopArrival<T>[][]) new StructStopArrival[nRounds][stops];
    }

    @Override
    public void setInitialTime(int round, int stop, int time) {
        findOrCreateStopIndex(round, stop).setTime(time);
    }

    @Override
    public void transitToStop(int round, int stop, int time, int boardStop, int boardTime, T trip, boolean bestTime) {
        StructStopArrival<T> state = findOrCreateStopIndex(round, stop);

        state.arriveByTransit(time, boardStop, boardTime, trip);

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
        StructStopArrival state = findOrCreateStopIndex(round, stop);

        state.transferToStop(fromStop, arrivalTime, toStopArrival.durationInSeconds());
    }

    public final int time(int round, int stop) {
        return stops[round][stop].time();
    }

    public Cursor newCursor() {
        return new Cursor();
    }

    private StructStopArrival<T> findOrCreateStopIndex(final int round, final int stop) {
        if(stops[round][stop] == null) {
            stops[round][stop] = new StructStopArrival<T>();
        }
        return stops[round][stop];
    }

    public class Cursor implements StopArrivalCursor<T> {

        public StructStopArrival<T> stop(int round, int stop) {
            return stops[round][stop];
        }

        @Override
        public boolean stopNotVisited(int round, int stop) {
            return stops[round][stop] == null;
        }
    }
}
