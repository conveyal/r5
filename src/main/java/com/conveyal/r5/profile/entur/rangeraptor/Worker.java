package com.conveyal.r5.profile.entur.rangeraptor;


import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

import java.util.Collection;

public interface Worker<T extends TripScheduleInfo> {
    Collection<Path2<T>> route();
}
