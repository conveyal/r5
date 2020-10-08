package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.util.Collection;
import java.util.Map;

/**
 * This is the internal form of a PickupDelay modification that has been resolved against a particular TransportNetwork.
 * TODO rename to OnDemandFeederService
 */
public class PickupWaitTimes {

    // TODO we can build this from the ModificationPolygons, which allows indexing only the keys not values.
    // TODO handle the case where there are no stop restrictions on the polygons.
    // TODO parameterize IndexedPolygonCollection to map polygons to arbitrary types
    private final IndexedPolygonCollection polygons;

    /**
     * This Map associates each on-demand pick-up zone with specific internal stop indexes in the TransitLayer.
     * On the access leg, someone picked up in the key zone can be dropped off at any of the transit stops that are
     * values for that key. On the egress end, this is reversed. Because this map is used on both the access and egress
     * ends, it should contain even polygons whose wait time data is negative (indicating they can't be used on access)
     * because they may still be used for egress. TODO Clarify -- this implies stop zones should have delay = -1? But
     * that's not congruent with egressWaitMinutes >= 0 in PickupDelay.
     * If the Map is not present (null) then all the zones allow access to any stop in the network.
     */
    private final Map<ModificationPolygon, TIntSet> stopNumbersForZonePolygon;

    private final TIntObjectMap<EgressService> egressServiceForStop;

    // A temporary reversed Multimap should be made, maybe only when building the egress tables, mapping each stop
    // zone to all service polygons attached to it. A one-to-many relationship is possible because there is only one
    // delay, the delay for the stop polygon (not the delays for the service polygons, i.e. all delays are for pickup
    // only).
    // We also need to heavily reuse the wait times at egress stops... but again those are reused when making the egress
    // tables, after that they are baked into the tables. So the index of wait times at stops and spatial index are only
    // used at that point, when rebuilding a scenario egress table (and all of those are done at once).
    // For a given stop number, we can return a delay time, which must be added to travel times to all targets
    // (or street vertices) within the service polygon; all others should be marked unreachable.

    public final StreetMode streetMode;

    // We could pass in a Collection<AccessService> as well.
    public PickupWaitTimes (
        IndexedPolygonCollection polygons,
        Map<ModificationPolygon, TIntSet> stopNumbersForZonePolygon,
        Collection<EgressService> egressServices,
        StreetMode streetMode
    ) {
        this.polygons = polygons;
        this.stopNumbersForZonePolygon = stopNumbersForZonePolygon;
        this.egressServiceForStop = new TIntObjectHashMap<>();
        for (EgressService egressService : egressServices) {
            egressService.egressStops.forEach(stop -> {
                egressServiceForStop.put(stop, egressService);
                return true;
            });
        }
        this.streetMode = streetMode;
    }

    /**
     * Given a particular departure location, get a description of the on-demand pickup service available there.
     * Currently this chooses just one "best" zone polygon based on location and priority values in the polygons.
     * @return an AccessService with the wait time to be picked up, and any restrictions on reachable stops.
     */
    public AccessService getAccessService (double lat, double lon) {
        Point point = GeometryUtils.geometryFactory.createPoint(new Coordinate(lon, lat));
        ModificationPolygon polygon = polygons.getWinningPolygon(point);
        if (polygon == null || polygon.data == -1) {
            return NO_SERVICE_HERE;
        }
        // Service is available here. Determine the waiting time, and any restrictions on which stops can be reached.
        // By default all stops can be reached (null means no restrictions applied).
        int waitTimeSeconds = (int) (polygon.data * 60);
        TIntSet stopsReachable = null;
        // If an association has been made between pickup polygons and stop polygons, that restricts reachable stops.
        if (stopNumbersForZonePolygon != null) {
            stopsReachable = stopNumbersForZonePolygon.get(polygon);
            if (stopsReachable == null) {
                // No stops were associated with the winning polygon (e.g. it is itself a stop polygon).
                // stopsReachable should be empty, since null signals "no filtering" (all stops reachable).
                stopsReachable = new TIntHashSet();
            }
        }
        return new AccessService(waitTimeSeconds, stopsReachable);
    }

    /**
     * Get an EgressService object describing on-demand mobility service departing from the given stop number, or null
     * if no service is available
     */
    public EgressService getEgressService (int stopNumber) {
        return egressServiceForStop.get(stopNumber);
    }

    // TODO superclass Service contains all fields, and is the type of these two constants?

    /** Special instance representing situations where a service is defined, but not available at this location. */
    public static final AccessService NO_SERVICE_HERE = new AccessService(-1, null);

    /** Special instance representing no on-demand service defined, so we can access all stops with no wait. */
    public static final AccessService NO_WAIT_ALL_STOPS = new AccessService(0, null);

    /**
     * This represents an on-demand service available at a particular departure location.
     * This is the result of evaluating the PickupWaitTimes at a particular place.
     * Alternatively instead of defining this data-holder class we could allow passing a travel time map into a method
     * on this class, and have this class transform it.
     */
    public static class AccessService {

        /**
         * The amount of time you have to wait at this location (in seconds) to be picked up on demand. Zero if you can
         * be picked up immediately (e.g. a taxi stand outside a station), -1 if no service is available at all.
         */
        public final int waitTimeSeconds;

        /**
         * If a limitation is placed on the transit stops one is allowed to access using this service, this is the
         * set of allowed stops. This is null if all stops are reachable and no filtering should happen.
         * If we eventually want to reflect multiple services with different waits, we could instead return a
         * TIntIntMap from allowed stop indexes to wait times.
         */
        public final TIntSet stopsReachable;

        public AccessService (int waitTimeSeconds, TIntSet stopsReachable) {
            this.waitTimeSeconds = waitTimeSeconds;
            this.stopsReachable = stopsReachable;
        }
    }

    // TODO pull all of these classes out into an on-demand Java package
    // It's a bit weird that EgressServices are pre-computed and AccessService instances are built on demand.
    // The two classes could be almost identical with the exception of comments. One could extend the other.
    public static class EgressService {

        public final int waitTimeSeconds;

        public final TIntSet egressStops;

        /**
         * The geographic area one is allowed to access using this service. If null, no geographic restriction applies.
         * This may be the union of several polygons that were all associated with one stop or group of stops.
         * In floating point WGS84 (lon, lat) coordinates. Should be a polygon or multipolygon.
         */
        public final Geometry serviceArea;

        public EgressService (int waitTimeSeconds, TIntSet egressStops, Geometry serviceArea) {
            if (waitTimeSeconds < 0) throw new AssertionError("Wait times should always be non-negative.");
            this.waitTimeSeconds = waitTimeSeconds;
            this.egressStops = egressStops;
            this.serviceArea = serviceArea;
        }

    }

    /**
     * Construct an inverse map of stopNumbersForZonePolygon, for use on the egress side of an on-demand feeder
     * service. This derived data structure is not cached because it is not frequently used. Its contents are baked
     * into egress cost tables when they are constructed.
     */
    public final TIntObjectMap<EgressService> getEgressServices () {
        return egressServiceForStop;
    }

}
