package com.conveyal.r5.otp2.rangeraptor.path;

import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;

/**
 * Responsible for mapping between the domain of routing to the domain of result paths.
 * Especially a regular forward search and a reverse search have different internal
 * data representations (latest possible arival times vs. arrival times); Hence one mapper
 * for each.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
@FunctionalInterface
public interface PathMapper<T extends TripScheduleInfo> {
    /**
     * Build a path from a destination arrival - this maps between the domain of routing
     * to the domain of result paths. All values not needed for routing is computed as part of this mapping.
     */
     Path<T> mapToPath(final DestinationArrival<T> destinationArrival);
}
