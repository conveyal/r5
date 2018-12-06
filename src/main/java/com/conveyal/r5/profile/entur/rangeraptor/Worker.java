package com.conveyal.r5.profile.entur.rangeraptor;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.Collection;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public interface Worker<T extends TripScheduleInfo> {
    Collection<Path<T>> route();
}
