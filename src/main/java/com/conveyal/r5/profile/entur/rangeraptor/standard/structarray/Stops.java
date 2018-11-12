package com.conveyal.r5.profile.entur.rangeraptor.standard.structarray;


import com.conveyal.r5.profile.entur.api.TransferLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalCursor;


public final class Stops<T extends TripScheduleInfo> {

    private final StopArrival<T>[][] stops;


    public Stops(int nRounds, int stops) {
        //noinspection unchecked
        this.stops = (StopArrival<T>[][]) new StopArrival[nRounds][stops];
    }

    public void setInitialTime(int round, int stop, int time) {
        findOrCreateStopIndex(round, stop).setTime(time);
    }

    public void transitToStop(int round, int stop, int time, int boardStop, int boardTime, T trip, boolean bestTime) {
        StopArrival<T> state = findOrCreateStopIndex(round, stop);

        state.arriveByTransit(time, boardStop, boardTime, trip);

        if(bestTime) {
            state.setBestTimeTransit(time);
        }
    }

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    public void transferToStop(int round, int fromStop, TransferLeg transferLeg, int arrivalTime) {
        int stop = transferLeg.stop();
        StopArrival state = findOrCreateStopIndex(round, stop);

        state.transferToStop(fromStop, arrivalTime, transferLeg.durationInSeconds());
    }

    public final int time(int round, int stop) {
        return stops[round][stop].time();
    }

    public Cursor newCursor() {
        return new Cursor();
    }

    private StopArrival<T> findOrCreateStopIndex(final int round, final int stop) {
        if(stops[round][stop] == null) {
            stops[round][stop] = new StopArrival<T>();
        }
        return stops[round][stop];
    }

    public class Cursor implements StopArrivalCursor<T> {

        public StopArrival<T> stop(int round, int stop) {
            return stops[round][stop];
        }

        @Override
        public boolean stopNotVisited(int round, int stop) {
            return stops[round][stop] == null;
        }
    }
}
