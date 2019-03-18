package com.conveyal.r5.profile.entur.api.view;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.Collection;

/**
 * The worker perform the travel search. There are multiple implementation,
 * even some who do not return paths.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public interface Worker<T extends TripScheduleInfo> {

    /**
     * Perform the reouting request.
     * @return All paths found. Am empty set is returned if no patha are forund or
     * the algorithm do not collect paths.
     */
    Collection<Path<T>> route();
}
