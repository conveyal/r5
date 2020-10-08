package com.conveyal.r5.analyst.scenario;

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
     * A JSON map from polygon IDs to lists of polygon IDs. If any stop_id is specified for a polygon, service is
     * only allowed between the polygon and the stops (i.e. no direct trips). If no stop_ids are specified,
     * passengers boarding an on-demand service in a pick-up zone should be able to alight anywhere.
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
            errors.addAll(polygons.getErrors());
            // Handle pickup service to stop mapping if supplied in the modification JSON.
            if (stopsForZone == null) {
                this.pickupWaitTimes = new PickupWaitTimes(polygons, null, Collections.emptySet(), this.streetMode);
            } else {
                // Iterate over all zone-stop mappings and resolve them against the network.
                // Because they are used in lambda functions, these variables must be final and non-null.
                // That is why there's a separate PickupWaitTimes constructor call above with a null parameter.
                final Map<ModificationPolygon, TIntSet> stopNumbersForZonePolygon = new HashMap<>();
                final Map<ModificationPolygon, PickupWaitTimes.EgressService> egressServices = new HashMap<>();
                if (stopsForZone.isEmpty()) {
                    errors.add("If stopsForZone is specified, it must be non-empty.");
                }
                stopsForZone.forEach((zonePolygonId, stopPolygonIds) -> {
                    ModificationPolygon zonePolygon = polygons.getById(zonePolygonId);
                    if (zonePolygon == null) {
                        errors.add("Could not find zone polygon with ID: " + zonePolygonId);
                    }
                    TIntSet stopNumbers = stopNumbersForZonePolygon.get(zonePolygon);
                    if (stopNumbers == null) {
                        stopNumbers = new TIntHashSet();
                        stopNumbersForZonePolygon.put(zonePolygon, stopNumbers);
                    }
                    for (String stopPolygonId : stopPolygonIds) {
                        ModificationPolygon stopPolygon = polygons.getById(stopPolygonId);
                        if (stopPolygon == null) {
                            errors.add("Could not find stop polygon with ID: " + stopPolygonId);
                        }
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
                            PickupWaitTimes.EgressService egressService = egressServices.get(stopPolygon);
                            if (egressService != null) {
                                // Merge service are with any other service polygons associated with this stop polygon.
                                serviceArea = serviceArea.union(egressService.serviceArea);
                            }
                            egressService = new PickupWaitTimes.EgressService(egressWaitSeconds, stops, serviceArea);
                            egressServices.put(stopPolygon, egressService);
                        }
                    }
                });
                // TODO filter out polygons that aren't keys in stopsForZone using new IndexedPolygonCollection constructor
                // egress wait times for stop numbers
                this.pickupWaitTimes = new PickupWaitTimes(
                        polygons,
                        stopNumbersForZonePolygon,
                        egressServices.values(),
                        this.streetMode
                );
            }
        } catch (Exception e) {
            // Record any unexpected errors to bubble up to the UI.
            errors.add(ExceptionUtils.asString(e));
        }
        return errors.size() > 0;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        // network.streetLayer is already a protective copy made by method Scenario.applyToTransportNetwork.
        // The polygons have already been validated in the resolve method, we just need to record them in the network.
        if (network.streetLayer.pickupWaitTimes != null) {
            errors.add("Multiple pickup delay modifications cannot be applied to a single network.");
        } else {
            network.streetLayer.pickupWaitTimes = this.pickupWaitTimes;
        }
        return errors.size() > 0;
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
