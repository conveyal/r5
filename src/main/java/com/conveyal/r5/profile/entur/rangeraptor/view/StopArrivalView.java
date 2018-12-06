package com.conveyal.r5.profile.entur.rangeraptor.view;


import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public interface StopArrivalView<T extends TripScheduleInfo> {


    /**
     * Stop index where the arrival takes place
     */
    int stop();

    /**
     * The Range Raptor ROUND this stop is reached
     */
    int round();

    /**
     * The last leg departure time.
     */
    int departureTime();

    /**
     * The arrival time for when the stop is reached
     */
    int arrivalTime();

    /**
     * The accumulated cost
     */
    default int cost() {
        return 0;
    }

    /**
     * Departure time from origin, time-shifted towards the transit board time.
     */
    default int departureTimeAccess(int transitBoardTime) {
        throw new UnsupportedOperationException();
    }

    /**
     * The arrival time at the access stop, time-shifted towards the transit board time.
     */
    default int arrivalTimeAccess(int transitBoardTime) {
        throw new UnsupportedOperationException();
    }

    /**
     * The duration of the last leg
     */
    default int legDuration() {
        return arrivalTime() - departureTime();
    }

    /**
     * The previous stop arrival state
     */
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
