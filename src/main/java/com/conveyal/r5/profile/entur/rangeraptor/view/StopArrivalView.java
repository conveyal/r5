package com.conveyal.r5.profile.entur.rangeraptor.view;


import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TODO TGR
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
    StopArrivalView<T> previous();

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


    /**
     * List all stops used to arrive at current stop arrival. This method is SLOW,
     * should only be used in code that need to be fast, like debugging.
     */
    default List<Integer> listStops() {
        List<Integer> stops = new ArrayList<>();

        StopArrivalView<T> arrival = this;

        while (!arrival.arrivedByAccessLeg()) {
            stops.add(arrival.stop());
            arrival = arrival.previous();
        }
        stops.add(arrival.stop());

        Collections.reverse(stops);

        return stops;
    }

    /**
     * Describe type of leg/mode. This is used for logging/debugging.
     */
    default String legType() {
        if (arrivedByAccessLeg()) {
            return "Access";
        }
        if (arrivedByTransit()) {
            return "Transit";
        }
        // We use Walk instead of Transfer so it is easier to distinguish from Transit
        if (arrivedByTransfer()) {
            return  "Walk";
        }
        throw new IllegalStateException("Unknown mode for: " + this);
    }


    default String asString() {
        return String.format(
                "%s { Rnd: %d, Stop: %d, Time: %s (%s), Cost: %d }",
                getClass().getSimpleName(),
                round(),
                stop(),
                TimeUtils.timeToStrCompact(arrivalTime()),
                TimeUtils.timeToStrCompact(legDuration()),
                cost()
        );
    }
}
