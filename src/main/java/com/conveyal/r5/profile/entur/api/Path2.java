package com.conveyal.r5.profile.entur.api;

public interface Path2<T extends TripScheduleInfo> {

    PathLeg<T> accessLeg();

    Iterable<PathLeg<T>> legs();

    PathLeg<T> egressLeg();

}
