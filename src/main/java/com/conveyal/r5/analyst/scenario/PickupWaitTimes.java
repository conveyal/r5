package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.analyst.scenario.ondemand.AccessService;
import com.conveyal.r5.analyst.scenario.ondemand.EgressService;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.conveyal.r5.analyst.scenario.ondemand.AccessService.NO_SERVICE_HERE;

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
     * values for that key. On the egress end, this is reversed.
     * If this and destinationAreasForZonePolygon are both null, then all the zones allow access to any stop in the
     * network.
     */
    private final Map<ModificationPolygon, TIntSet> stopNumbersForZonePolygon;

    /**
     * Map associating each on-demand pick-up zone with specific areas to which direct service (without using a
     * scheduled transit mode) is provided. If this and stopNumbersForZonePolygon are both null, then all the zones
     * provide service to all destinations.
     */
    private final Map<ModificationPolygon, Geometry> destinationAreasForZonePolygon;

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
        Map<ModificationPolygon, Geometry> destinationAreasForZonePolygon,
        Collection<EgressService> egressServices,
        StreetMode streetMode
    ) {
        this.polygons = polygons;
        this.stopNumbersForZonePolygon = stopNumbersForZonePolygon;
        this.destinationAreasForZonePolygon = destinationAreasForZonePolygon;
        this.egressServiceForStop = new TIntObjectHashMap<>();
        for (EgressService egressService : egressServices) {
            egressService.stops.forEach(stop -> {
                egressServiceForStop.put(stop, egressService);
                return true;
            });
        }
        this.streetMode = streetMode;
    }

    /**
     * Given a particular departure location, get a description of the on-demand pickup services available there.
     * @return an AccessService with the wait time to be picked up, and any restrictions on reachable stops and
     * service areas
     */
    public AccessService getAccessService (double lat, double lon) {
        Point point = GeometryUtils.geometryFactory.createPoint(new Coordinate(lon, lat));
        List<ModificationPolygon> intersectingPolygons = polygons.getIntersectingPolygons(point);

        TIntIntMap waitTimesForStops = new TIntIntHashMap();

        if (intersectingPolygons.size() == 0 && polygons.defaultData == -1) {
            return NO_SERVICE_HERE;
        }

        for (ModificationPolygon polygon : intersectingPolygons) {
            double waitTimeMinutes = polygon.data;
            if (waitTimeMinutes == -1) {
                return NO_SERVICE_HERE;
            }
            // Service is available here. Determine the waiting time, and any restrictions on which stops can be reached.
            // By default all stops can be reached (null means no restrictions applied).
            int waitTimeSeconds = (int) (waitTimeMinutes * 60);
            // If an association has been made between pickup polygons and stop polygons, that restricts reachable stops.
            if (stopNumbersForZonePolygon != null) {
                TIntSet stopsReachable = stopNumbersForZonePolygon.get(polygon);
                if (stopsReachable != null) {
                    stopsReachable.forEach(stop -> {
                        waitTimesForStops.put(stop, waitTimeSeconds);
                        return true;
                    });
                }
            }
        }
        return new AccessService(waitTimesForStops);
    }

    /**
     * Get an EgressService object describing on-demand mobility service departing from the given stop number, or null
     * if no service is available
     */
    public EgressService getEgressService (int stopNumber) {
        return egressServiceForStop.get(stopNumber);
    }

    public int getDefaultWaitInSeconds() {
        return (int) (polygons.defaultData * 60);
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
