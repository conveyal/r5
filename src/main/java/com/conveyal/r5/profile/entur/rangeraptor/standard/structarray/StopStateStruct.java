package com.conveyal.r5.profile.entur.rangeraptor.standard.structarray;

import com.conveyal.r5.profile.entur.rangeraptor.standard.StopState;

class StopStateStruct implements StopState {
    int time = UNREACHED;
    int transitTime = UNREACHED;
    int previousPattern = NOT_SET;
    int previousTrip = NOT_SET;
    int boardTime = UNREACHED;
    int transferTime = NOT_SET;
    int boardStop = NOT_SET;
    int transferFromStop = NOT_SET;

    @Override
    public final int time() {
        return time;
    }

    @Override
    public int transitTime() {
        return transitTime;
    }

    @Override
    public boolean arrivedByTransit() {
        return transitTime != UNREACHED;
    }

    @Override
    public int pattern() {
        return previousPattern;
    }

    @Override
    public int trip() {
        return previousTrip;
    }

    @Override
    public int transferTime() {
        return transferTime;
    }

    @Override
    public int boardStop() {
        return boardStop;
    }

    @Override
    public int boardTime() {
        return boardTime;
    }

    @Override
    public int transferFromStop() {
        return transferFromStop;
    }

    @Override
    public boolean arrivedByTransfer() {
        return transferFromStop != NOT_SET;
    }

    @Override
    public String toString() {
        return asString("struct array", -1, -1);
    }

    void arriveByTransit(int time, int boardStop, int boardTime, int pattern, int trip) {
        this.transitTime = time;
        this.previousPattern = pattern;
        this.previousTrip = trip;
        this.boardTime = boardTime;
        this.boardStop = boardStop;
    }

    void setBestTimeTransit(int time) {
        this.time = time;
        this.transferFromStop = NOT_SET;
    }

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    public void transferToStop(int fromStop, int arrivalTime, int transferTime) {
        this.time = arrivalTime;
        this.transferFromStop = fromStop;
        this.transferTime = transferTime;
    }
}
