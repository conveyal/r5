package com.conveyal.r5.profile.entur.rangeraptor.view;


import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.LinkedList;

/**
 * The purpose of the stop arrival view is to provide a simplified uniform normalized
 * interface for the internal Raptor specific models. The models are optimized for
 * speed and memory consumtion, while the view provide one interface for mapping back
 * to the users domain. The view is used by the debugging functionality and mapping to
 * paths.
 * <p/>
 * The view are only created for objects part of a path to be returned or a stop arrival
 * part of some debug operations. This is just a fraction of all stop arrivals so there
 * is no need to optimize performance nor memory consumption fo view objects.
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
     * should only be used in code that does not need to be fast, like debugging.
     */
    default Iterable<Integer> listStopsForDebugging() {
        LinkedList<Integer> stops = new LinkedList<>();

        StopArrivalView<T> it = this;

        while (!it.arrivedByAccessLeg()) {
            stops.addFirst(it.stop());
            it = it.previous();
        }
        stops.addFirst(it.stop());

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
                TimeUtils.timeToStrCompact(departureTime()),
                cost()
        );
    }
}
