package com.conveyal.r5.profile.entur.rangeraptor.view;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.TimeUtils;

/**
 * TODO TGR
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public interface DestinationArrivalView<T extends TripScheduleInfo> {

    int departureTime();

    int arrivalTime();

    StopArrivalView<T> previous();

    default int cost() {
        return 0;
    }

    default String asString() {
        return String.format(
                "%s { Time: %s (%s), Cost: %d }",
                getClass().getSimpleName(),
                TimeUtils.timeToStrCompact(arrivalTime()),
                TimeUtils.timeToStrCompact(departureTime()),
                cost()
        );
    }
}
