package com.conveyal.r5.profile.entur.rangeraptor.view;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

public interface DestinationArrivalView<T extends TripScheduleInfo> {

    int departureTime();

    int arrivalTime();

    StopArrivalView<T> previous();
}
