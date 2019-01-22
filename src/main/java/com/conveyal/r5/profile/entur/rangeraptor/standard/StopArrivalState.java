package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.IntUtils;
import com.conveyal.r5.profile.entur.util.TimeUtils;


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
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class StopArrivalState<T extends TripScheduleInfo> {
    /**
     * Uninitialized time values is set to this value to mark them as not set, and to mark the
     * arrival as unreached. A big value is used to simplify the comparisons to see if a new
     * arrival time is better (less).
     * <p/>
     * This value essentially serves as Infinity for ints - it's bigger than every other number.
     * It is the travel time to a transit stop or a target before that stop or target is ever reached.
     */
    private static final int UNREACHED = Integer.MAX_VALUE;

    /**
     * Used to initialize all none time based attributes.
     */
    private static final int NOT_SET = -1;


    // Best time - access, transit or transfer
    private int bestArrivalTime = UNREACHED;

    // Transit
    private int transitArrivalTime = UNREACHED;
    private T trip = null;
    private int boardTime = UNREACHED;
    private int boardStop = NOT_SET;

    // Transfer
    private int transferFromStop = NOT_SET;
    private int accessOrTransferDuration = NOT_SET;

    final int time() {
        return bestArrivalTime;
    }

    final int accessDuration() {
        return accessOrTransferDuration;
    }

    final int accessDepartureTime() {
        return bestArrivalTime - accessOrTransferDuration;
    }

    final int transitTime() {
        return transitArrivalTime;
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
        return accessOrTransferDuration;
    }

    final boolean arrivedByAccess() {
        return !arrivedByTransfer() && accessOrTransferDuration != NOT_SET;
    }

    final boolean arrivedByTransit() {
        return transitArrivalTime != UNREACHED;
    }

    final boolean arrivedByTransfer() {
        return transferFromStop != NOT_SET;
    }

    final void setAccessTime(int time, int accessDuration) {
        this.bestArrivalTime = time;
        this.accessOrTransferDuration = accessDuration;
    }

    final boolean reached() {
        return bestArrivalTime != UNREACHED;
    }

    void arriveByTransit(int time, int boardStop, int boardTime, T trip) {
        this.transitArrivalTime = time;
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
        this.accessOrTransferDuration = transferTime;
    }

    @Override
    public String toString() {
        if(arrivedByAccess()) {
            // TODO TGR REVERSE ...
            return String.format("Access Arrival { time: %s, duration: %s }",
                    TimeUtils.timeToStrLong(bestArrivalTime),
                    TimeUtils.timeToStrCompact(accessOrTransferDuration)
            );
        }
        // TODO TGR REVERSE ...
        return String.format("Arrival { time: %s, Transit: %s %s-%s, trip: %s, Transfer from: %s %s }",
                TimeUtils.timeToStrLong(bestArrivalTime, UNREACHED),
                IntUtils.intToString(boardStop, NOT_SET),
                TimeUtils.timeToStrCompact(boardTime, UNREACHED),
                TimeUtils.timeToStrCompact(transitArrivalTime, UNREACHED),
                trip == null ? "" : trip.debugInfo(),
                IntUtils.intToString(transferFromStop, NOT_SET),
                TimeUtils.timeToStrCompact(accessOrTransferDuration, NOT_SET)
        );
    }
}
