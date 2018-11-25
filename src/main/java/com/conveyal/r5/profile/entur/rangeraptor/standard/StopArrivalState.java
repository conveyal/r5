package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.IntUtils;
import com.conveyal.r5.profile.entur.util.TimeUtils;

import static com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView.NOT_SET;
import static com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView.UNREACHED;


/**
 * This class main purpose is to hold data for a given arrival at a stop and raptor round. It should be as light
 * weight as possible to minimize memory consumption and cheap to create and garbage collect.
 * <p/>
 * This class holds both the best transit and the best transfer to a stop if they exist for a given round and stop.
 * The normal case is that this class represent either a transit arrival or a transfer arrival. We only keep both
 * if the transfer is better, arriving before the transit.
 * <p/>
 * The reason we need to keep both the best transfer and the best transit for a given stop and round is that
 * we may arrive at a stop by transit, then in the same or later round we may arrive by transit. If the transfer
 * arrival is better then the transit arrival it might be tempting to remove the transit arrival, but this
 * transit might be the best way (or only way) to get to another stop by transfer.
 */
class StopArrivalState<T extends TripScheduleInfo> {
    // Best time
    private int bestArrivalTime = UNREACHED;

    // Transit
    private int transitTime = UNREACHED;
    private T trip = null;
    private int boardTime = UNREACHED;
    private int boardStop = NOT_SET;

    // Transfer
    private int transferFromStop = NOT_SET;
    private int transferDuration = NOT_SET;

    final int time() {
        return bestArrivalTime;
    }

    final int transitTime() {
        return transitTime;
    }

    final T trip() {
        return trip;
    }

    final int boardTime() {
        return boardTime;
    }

    final int boardStop() {
        return boardStop;
    }

    final int transferFromStop() {
        return transferFromStop;
    }

    final int transferDuration() {
        return transferDuration;
    }

    final boolean arrivedByTransit() {
        return transitTime != UNREACHED;
    }

    final boolean arrivedByTransfer() {
        return transferFromStop != NOT_SET;
    }

    final void setAccessTime(int time) {
        this.bestArrivalTime = time;
    }

    void arriveByTransit(int time, int boardStop, int boardTime, T trip) {
        this.transitTime = time;
        this.trip = trip;
        this.boardTime = boardTime;
        this.boardStop = boardStop;
    }

    final void setBestTimeTransit(int time) {
        this.bestArrivalTime = time;
        // The transfer is cleared since it is not the fastest alternative any more.
        this.transferFromStop = NOT_SET;
    }

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    final void transferToStop(int fromStop, int arrivalTime, int transferTime) {
        this.bestArrivalTime = arrivalTime;
        this.transferFromStop = fromStop;
        this.transferDuration = transferTime;
    }

    @Override
    public String toString() {
        return String.format("RR Arrival { time: %s, Transit { stop: %s, time: %s - %s, trip: %s }, Transfer { from stop: %s, duration: %s }",
                TimeUtils.timeToStrLong(bestArrivalTime, UNREACHED),
                IntUtils.intToString(boardStop, NOT_SET),
                TimeUtils.timeToStrCompact(boardTime, UNREACHED),
                TimeUtils.timeToStrCompact(transitTime, UNREACHED),
                trip == null ? "" : trip.debugInfo(),
                IntUtils.intToString(transferFromStop, NOT_SET),
                IntUtils.intToString(transferDuration, NOT_SET)
        );
    }
}
