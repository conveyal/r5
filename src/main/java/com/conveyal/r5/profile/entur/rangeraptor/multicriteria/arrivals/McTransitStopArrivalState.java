package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;


import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

public final class McTransitStopArrivalState<T extends TripScheduleInfo> extends McStopArrivalState<T> {
    private final int boardTime;
    private final T trip;

    public McTransitStopArrivalState(McStopArrivalState<T> previousState, int round, int stopIndex, int time, int boardTime, T trip) {
        super(previousState, round, round * 2, stopIndex, time, previousState.cost());
        this.trip = trip;
        this.boardTime = boardTime;
    }

    @Override
    public int transitTime() {
        return time();
    }

    @Override
    public boolean arrivedByTransit() {
        return true;
    }

    @Override
    public T trip() {
        return trip;
    }

    @Override
    public int boardStop() {
        return previousStop();
    }

    @Override
    public int boardTime() {
        return boardTime;
    }
}
