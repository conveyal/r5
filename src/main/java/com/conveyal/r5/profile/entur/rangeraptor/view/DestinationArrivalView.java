package com.conveyal.r5.profile.entur.rangeraptor.view;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

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
}
