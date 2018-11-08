package com.conveyal.r5.profile.entur.rangeraptor.standard.structarray;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.StopState;

final class StopStateStruct<T extends TripScheduleInfo> implements StopState<T> {
    private int time = UNREACHED;
    private int transitTime = UNREACHED;
    private T trip = null;
    private int boardTime = UNREACHED;
    private int transferTime = NOT_SET;
    private int boardStop = NOT_SET;
    private int transferFromStop = NOT_SET;

    @Override
    public final int time() {
        return time;
    }

    void setTime(int time) {
        this.time = time;
    }

    @Override
    public final int transitTime() {
        return transitTime;
    }

    @Override
    public final boolean arrivedByTransit() {
        return transitTime != UNREACHED;
    }

    @Override
    public final T trip() {
        return trip;
    }

    @Override
    public final int transferTime() {
        return transferTime;
    }

    @Override
    public final int boardStop() {
        return boardStop;
    }

    @Override
    public final int boardTime() {
        return boardTime;
    }

    @Override
    public final int transferFromStop() {
        return transferFromStop;
    }

    @Override
    public final boolean arrivedByTransfer() {
        return transferFromStop != NOT_SET;
    }

    @Override
    public String toString() {
        return asString("struct array", -1, -1);
    }

    final void arriveByTransit(int time, int boardStop, int boardTime, T trip) {
        this.transitTime = time;
        this.trip = trip;
        this.boardTime = boardTime;
        this.boardStop = boardStop;
    }

    final void setBestTimeTransit(int time) {
        this.time = time;
        this.transferFromStop = NOT_SET;
    }

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    final void transferToStop(int fromStop, int arrivalTime, int transferTime) {
        this.time = arrivalTime;
        this.transferFromStop = fromStop;
        this.transferTime = transferTime;
    }
}
