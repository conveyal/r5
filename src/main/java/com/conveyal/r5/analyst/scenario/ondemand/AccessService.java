package com.conveyal.r5.analyst.scenario.ondemand;

import gnu.trove.set.TIntSet;
import org.locationtech.jts.geom.Geometry;

/**
 * Represents an on-demand service available at a particular departure location. This is the result of evaluating the
 * PickupWaitTimes at a particular location.
 * Passengers can ride this service from this location directly to destinations in serviceArea (without riding transit),
 * and to specified stops (from which they can continue journeys via transit).
 * If stops and serviceArea are null, passengers can ride from this location directly to any destination.
 * Idea: instead of defining this data-holder class we could allow passing a travel time map
 * (PointSetTimes?) into a method on this class, and have this class transform it.
 */
public class AccessService extends OnDemandService {

    public AccessService (int waitTimeSeconds, TIntSet stopsReachable, Geometry directServiceArea) {
        this.waitTimeSeconds = waitTimeSeconds;
        this.stops = stopsReachable;
        this.serviceArea = directServiceArea;
    }

    /** Special instance representing situations where a service is defined, but not available at this location. */
    public static final AccessService NO_SERVICE_HERE = new AccessService(-1, null, null);

    /** Special instance representing no on-demand service defined, so we can access all stops with no wait. */
    public static final AccessService NO_WAIT_ALL_STOPS = new AccessService(0, null, null);

}
