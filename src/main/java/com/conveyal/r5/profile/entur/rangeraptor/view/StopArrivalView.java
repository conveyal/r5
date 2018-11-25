package com.conveyal.r5.profile.entur.rangeraptor.view;


import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

public interface StopArrivalView<T extends TripScheduleInfo> {
    /**
     * Uninitialized
     * This value essentially serves as Infinity for ints - it's bigger than every other number.
     * It is the travel time to a transit stop or a target before that stop or target is ever reached.
     * Be careful when propagating travel times from stops to targets, adding something to UNREACHED
     * will cause an overflow error.
     */
    int UNREACHED = Integer.MAX_VALUE;

    /**
     * Used to initialize all none time based attributes.
     */
    int NOT_SET = -1;


    int stop();

    int round();

    int departureTime();

    int arrivalTime();

    default int legDuration() {
        return arrivalTime() - departureTime();
    }

    default StopArrivalView<T> previous() {
        throw new UnsupportedOperationException();
    }

    /* Access stop arrival */

    /**
     * First stop arrival, arrived by a given access leg.
     */
    default boolean arrivedByAccessLeg() {
        return false;
    }

    /* Transit */

    default boolean arrivedByTransit() {
        return false;
    }

    default int boardStop() {
        throw new UnsupportedOperationException();
    }

    default T trip() {
        throw new UnsupportedOperationException();
    }

    /* Transfer */

    default boolean arrivedByTransfer() {
        return false;
    }

    default int transferFromStop() {
        throw new UnsupportedOperationException();
    }
}
