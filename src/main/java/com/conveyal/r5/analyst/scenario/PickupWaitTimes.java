package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Point;
import gnu.trove.list.TIntList;
import gnu.trove.set.TIntSet;

import java.util.Map;

/**
 * This is the internal form of a PickupDelay modification that has been resolved against a particular TransportNetwork.
 * TODO rename to OnDemandFeeder
 */
public class PickupWaitTimes {

    private final IndexedPolygonCollection polygons;

    /**
     * This Map associates each on-demand zone with specific stops in the TransitLayer.
     * On the access leg, someone picked up in the key zone can be dropped off
     * On the egress end, this is reversed.
     * If the Map is not present (null) then all the zonePolygons allow access to all stopPolygons; if the stopPolygons
     * are also not supplied then all zones allow access to any stop in the network.
     */
    private final Map<ModificationPolygon, TIntSet> stopNumbersForZonePolygon;

    public final StreetMode streetMode;

    public PickupWaitTimes (
        IndexedPolygonCollection polygons,
        Map<ModificationPolygon, TIntSet> stopNumbersForZonePolygon,
        StreetMode streetMode
    ) {
        this.polygons = polygons;
        this.stopNumbersForZonePolygon = stopNumbersForZonePolygon;
        this.streetMode = streetMode;
    }

    /**
     * This represents the PickupWaitTimes evaluated at a particular departure location.
     * One zone polygon will be chosen representing a particular service.
     * @return the wait time to be picked up at the given point, or -1 if no service is available.
     */
    public AccessService getAccessService (double lat, double lon) {
        Point point = GeometryUtils.geometryFactory.createPoint(new Coordinate(lon, lat));
        ModificationPolygon polygon = polygons.getWinningPolygon(point);
        if (polygon == null || polygon.data == -1) {
            return NO_SERVICE_HERE;
        } else {
            int waitTimeSeconds = (int) (polygon.data * 60);
            TIntSet stopsReachable = stopNumbersForZonePolygon.get(polygon);
            return new AccessService(waitTimeSeconds, stopsReachable);
        }
    }

    /** Special instance representing situations where a service is defined, but not available at this location. */
    public static final AccessService NO_SERVICE_HERE = new AccessService(-1, null);

    /** Special instance representing no on-demand service defined, so we can access all stops with no wait. */
    public static final AccessService NO_WAIT_ALL_STOPS = new AccessService(0, null);

    public static class AccessService {
        public final int waitTimeSeconds;
        /**
         * The transit stops one is allowed to access using this service.
         * If null, all stops are reachable and no filtering should happen.
         * TODO we could instead return a TIntIntMap from stop indexes to wait times, reflecting multiple services.
         */
        public TIntSet stopsReachable;

        public AccessService (int waitTimeSeconds, TIntSet stopsReachable) {
            if (waitTimeSeconds < 0) throw new AssertionError("Wait times should always be non-negative.");
            this.waitTimeSeconds = waitTimeSeconds;
            this.stopsReachable = stopsReachable;
        }
    }
}
