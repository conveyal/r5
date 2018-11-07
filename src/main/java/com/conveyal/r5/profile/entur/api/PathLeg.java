package com.conveyal.r5.profile.entur.api;

public interface PathLeg<T extends TripScheduleInfo> {
    int fromStop();
    int fromTime();

    int toStop();
    int toTime();

    T trip();

    boolean isTransfer();
    boolean isTransit();
}
