package com.conveyal.r5.profile.entur.api;


import java.util.Collection;

public interface Worker<T extends TripScheduleInfo> {
    Collection<Path2<T>> route();
}
