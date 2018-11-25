package com.conveyal.r5.profile.entur.rangeraptor;


import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.api.path.Path;

import java.util.Collection;

public interface Worker<T extends TripScheduleInfo> {
    Collection<Path<T>> route();
}
