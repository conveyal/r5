package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.analyst.scenario.ondemand.EgressService;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.ExceptionUtils;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.conveyal.r5.profile.StreetMode.CAR;

/**
 * This Modification type configures the amount of time a passenger must wait to be picked up by a ride-hailing service.
 * This waiting time may vary spatially, and is specified with a set of polygons like the RoadCongestion modification.
 * See the documentation on that class for discussions on polygon priority. Eventually all the polygon priority and
 * indexing should be moved to a reusable class.
 *
 * TODO add a parameter for on-demand service slowdown factor? Rename to on-demand-feeder?
 */
public class PickupDelay extends Modification {

    private static final Logger LOG = LoggerFactory.getLogger(PickupDelay.class);

    // Public Parameters deserialized from JSON

    /**
     * The identifier of the polygon layer containing the on-demand pick-up zones.
     * This set of polygons represents areas where an on-demand mobility service will pick up passengers, with
     * associated wait times to be picked up. Overlapping zones are not yet supported, only one "winning" zone will
     * be chosen using the priority values.
     */
    public String zonePolygons;

    /**
     * The identifier of the polygon layer containing the stops associated with the zones, if any.
     * Having two different polygon files raises questions about whether the attribute names can be set separately,
     * and how we would factor out that aspect of the polygon loading code.
     */
    // public String stopPolygons;

    /**
     * The name of the attribute (floating-point) within the polygon layer that contains the pick-up wait time
     * (in minutes). Negative waiting times mean the area is not served at all.
     */
    public String waitTimeAttribute = "wait";

    /** The name of the attribute (numeric) within the polygon layer that contains the polygon priority. */
    public String priorityAttribute = "priority";

    /**
     * The name of the attribute (text) within the polygon layer that contains the polygon names. Only for logging.
     */
    public String nameAttribute = "name";

    /**
     * The name of the attribute (text) within the polygon layer that contains unique identifiers for the polygons.
     */
    public String idAttribute = "id";

    /**
     * A JSON map from polygon IDs to lists of polygon IDs, representing origin zones and allowable destination zones
     * for direct legs. For example, "a":["b","c"] allows passengers to travel from zone a to destinations in zones
     * b and c. For now, this does not enable use of stops in zones b and c for onward travel, so in most cases,
     * these zones should be explicitly repeated in stopsForZone.
     */
    public Map<String, Set<String>> destinationsForZone;

    /**
     * A JSON map from polygon IDs to lists of polygon IDs, representing origin zones and allowable boarding stops
     * for access legs. For example "a":["d","e"] allows passengers to travel from zone a to transit stops in zones d
     * and e.
     */
    public Map<String, Set<String>> stopsForZone;

    /**
     * StreetMode for which this set of pickup delays applies. No other modes will see the delays or associated
     * travel restrictions.
     */
    public StreetMode streetMode = CAR;

    /**
     * The default waiting time (floating point, in minutes) when no polygon is found. Negative numbers mean the area
     * is not served at all.
     */
    public double defaultWait = -1;

    // Internal (private and transient) fields used in applying the modification to the network

    /** The result of resolving this modification against the TransportNetwork. */
    private transient PickupWaitTimes pickupWaitTimes;

    /**
     * These polygons contain the stops served by the zones. Wait times are also loaded for these polygons and will be
     * used on the egress end of the itinerary.
     */
    //private transient IndexedPolygonCollection stopPolygons;

    // Implementations of methods for the Modification interface

    @Override
    public boolean resolve (TransportNetwork network) {
        // Polygon will only be fetched from S3 once when the scenario is resolved, then after application the
        // resulting network is cached. Subsequent uses of this same modification should not re-trigger S3 fetches.
        try {
            IndexedPolygonCollection polygons = new IndexedPolygonCollection(
                    zonePolygons,
                    waitTimeAttribute,
                    idAttribute,
                    nameAttribute,
                    priorityAttribute,
                    defaultWait
            );
            polygons.loadFromS3GeoJson();
            // Collect any errors from the IndexedPolygonCollection construction, so they can be seen in the UI.
            addErrors(polygons.getErrors());
            // Handle pickup service to stop mapping if supplied in the modification JSON.
            if (stopsForZone == null && destinationsForZone == null) {
                this.pickupWaitTimes = new PickupWaitTimes(
                        polygons,
                        null,
                        null,
                        Collections.emptySet(),
                        this.streetMode
                );
            } else {
                // Iterate over all zone-zone mappings and resolve them against the network.
                // Because they are used in lambda functions, these variables must be final and non-null.
                // That is why there's a separate PickupWaitTimes constructor call above with a null parameter.
                final Map<ModificationPolygon, TIntSet> stopNumbersForZonePolygon = new HashMap<>();
                final Map<ModificationPolygon, Geometry> destinationsForZonePolygon = new HashMap<>();
                final Map<ModificationPolygon, EgressService> egressServices = new HashMap<>();
                if (destinationsForZone != null) {
                    destinationsForZone.forEach((zonePolygonId, destinationPolygonIds) -> {
                        ModificationPolygon zonePolygon = tryToGetPolygon(polygons, zonePolygonId, "zone");
                        for (String id : destinationPolygonIds) {
                            ModificationPolygon destinationPolygon = tryToGetPolygon(polygons, id, "destination");
                            Geometry polygon = destinationsForZonePolygon.get(zonePolygon) == null ?
                                    destinationPolygon.polygonal :
                                    destinationsForZonePolygon.get(zonePolygon).union(destinationPolygon.polygonal);
                            destinationsForZonePolygon.put(zonePolygon, polygon);
                        }
                    });
                }
                if (stopsForZone != null) {
                    stopsForZone.forEach((zonePolygonId, stopPolygonIds) -> {
                        ModificationPolygon zonePolygon = tryToGetPolygon(polygons, zonePolygonId, "zone");
                        TIntSet stopNumbers = stopNumbersForZonePolygon.get(zonePolygon);
                        if (stopNumbers == null) {
                            stopNumbers = new TIntHashSet();
                            stopNumbersForZonePolygon.put(zonePolygon, stopNumbers);
                        }
                        for (String stopPolygonId : stopPolygonIds) {
                            ModificationPolygon stopPolygon = tryToGetPolygon(polygons, stopPolygonId, "stop");
                            TIntSet stops = network.transitLayer.findStopsInGeometry(stopPolygon.polygonal);
                            if (stops.isEmpty()) {
                                errors.add("Stop polygon did not contain any stops: " + stopPolygonId);
                            }
                            stopNumbers.addAll(stops);
                            // Derive egress services from this pair of polygons
                            double egressWaitMinutes = stopPolygon.data;
                            if (egressWaitMinutes >= 0) {
                                // This stop polygon can be used on the egress end of a trip.
                                int egressWaitSeconds = (int) (egressWaitMinutes * 60);
                                Geometry serviceArea = zonePolygon.polygonal;
                                EgressService egressService = egressServices.get(stopPolygon);
                                if (egressService != null) {
                                    // Merge service area with any other service polygons associated with this stop polygon.
                                    serviceArea = serviceArea.union(egressService.serviceArea);
                                }
                                egressService = new EgressService(egressWaitSeconds, stops, serviceArea);
                                egressServices.put(stopPolygon, egressService);
                            }
                        }

                    });
                }
                // TODO filter out polygons that aren't keys in stopsForZone using new IndexedPolygonCollection constructor
                this.pickupWaitTimes = new PickupWaitTimes(
                        polygons,
                        stopNumbersForZonePolygon,
                        destinationsForZonePolygon,
                        egressServices.values(),
                        this.streetMode
                );
            }
        } catch (Exception e) {
            // Record any unexpected errors to bubble up to the UI.
            addError(ExceptionUtils.stackTraceString(e));
        }
        return hasErrors();
    }

    private ModificationPolygon tryToGetPolygon (IndexedPolygonCollection polygons, String id, String label) {
        ModificationPolygon polygon = polygons.getById(id);
        if (polygon == null) {
            errors.add("Could not find " + label + " polygon with ID: " + id);
        }
        return polygon;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        // network.streetLayer is already a protective copy made by method Scenario.applyToTransportNetwork.
        // The polygons have already been validated in the resolve method, we just need to record them in the network.
        if (network.streetLayer.pickupWaitTimes != null) {
            addError("Multiple pickup delay modifications cannot be applied to a single network.");
        } else {
            network.streetLayer.pickupWaitTimes = this.pickupWaitTimes;
        }
        return hasErrors();
    }

    @Override
    public int getSortOrder () {
        // TODO Decide where this and other experiemental modification types should appear in the ordering.
        //      Note that it does need to be applied after any stops are created and added by other modifications.
        return 97;
    }

    @Override
    public boolean affectsStreetLayer () {
        // This modification only affects the waiting time to use on-street transport, but changes nothing at all about
        // scheduled public transit.
        return true;
    }

    @Override
    public boolean affectsTransitLayer () {
        // This modification only affects the waiting time to use on-street transport, but changes nothing at all about
        // scheduled public transit.
        return false;
    }


}
