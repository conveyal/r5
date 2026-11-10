package com.conveyal.r5.analyst;

import com.conveyal.gtfs.flex.OnDemand;
import com.conveyal.gtfs.flex.OnDemandEgressIndex;
import com.conveyal.gtfs.flex.OnDemandPlaceFilter;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.OnDemandEgressTable;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.conveyal.r5.profile.PerTargetPropagater.MM_PER_METER;
import static com.conveyal.r5.streets.OnDemandEgressTable.REFERENCE_BICYCLE_SPEED_METERS_PER_SECOND;

/// A selection of on-demand services and related lookups, narrowed to what one request can use
/// when evaluating egress legs during propagation. An instance is built once per request, just
/// before the per-target loops, and is discarded with the request. It is cheap to construct, as it
/// selects from the network's services and points to the ride cost tables ([OnDemandEgressTable]),
/// which are the expensive part of on-demand egress and are shared across requests.
///
/// The instance holds the candidate services that may pick up a rider alighting at each stop.
/// A service is a candidate when it is available on the request date and its pick-up window could
/// overlap the span of time in which a rider of this request might be ready to board, from the
/// start of the departure window to the latest departure plus the maximum trip duration. Each
/// service is tested independently against that span. Services are never compared with each other.
/// This pre-filter overselects, like the access side's candidate search. A more exact test is
/// applied during propagation, considering the actual alighting time for each iteration.
///
/// The instance also holds one drop-off place filter per candidate service, and the request's
/// walk speed and bike speed for converting the stored rides to durations.
///
/// The vehicles of different services can have different street modes (CAR or BICYCLE). Rides on
/// each mode come from a separate cost table on a separate linkage of the destinations. The
/// instance holds the linkage for each mode some candidate service uses.
///
/// Instances are not thread-safe. Place filters and the containment scratch objects are reused
/// across calls. The propagator processes targets sequentially in one thread.
public class OnDemandEgress {

    private final TIntObjectMap<List<OnDemand>> servicesForStop;

    private final Map<OnDemand, OnDemandPlaceFilter> dropOffPlaces;

    /// A destination linkage for each street mode used by a candidate service.
    /// Each linkage supplies an on-demand egress cost table for its mode.
    private final Map<StreetMode, LinkedPointSet> linkageForMode;

    /// This request's walk speed in millimeters per second,
    /// applied to the walk distances stored in the egress tables.
    private final int walkSpeedMillimetersPerSecond;

    /// The factor that converts ride seconds stored in a BICYCLE table, which are at the reference
    /// bicycle speed, to ride seconds at this request's bike speed.
    private final double bicycleRideScale;

    /// Scratch objects for place filter containment tests, reused across points.
    /// All linkages are for the same street layer, so one edge cursor serves them all.
    private final EdgeStore.Edge edgeScratch;
    private final Split splitScratch = new Split();

    private OnDemandEgress (
            TIntObjectMap<List<OnDemand>> servicesForStop,
            Map<OnDemand, OnDemandPlaceFilter> dropOffPlaces,
            Map<StreetMode, LinkedPointSet> linkageForMode,
            ProfileRequest request
    ) {
        this.servicesForStop = servicesForStop;
        this.dropOffPlaces = dropOffPlaces;
        this.linkageForMode = linkageForMode;
        this.walkSpeedMillimetersPerSecond = (int) (request.walkSpeed * MM_PER_METER);
        this.bicycleRideScale = REFERENCE_BICYCLE_SPEED_METERS_PER_SECOND / request.bikeSpeed;
        this.edgeScratch = linkageForMode.values().iterator().next().streetLayer.edgeStore.getCursor();
    }

    /// Prepare on-demand egress evaluation for one request, or return null when no service can
    /// carry any alighting rider on the request date.
    /// The calendar and window pre-filter overselects like the access side's candidate search.
    /// Exact per-iteration window tests are applied during propagation.
    public static OnDemandEgress prepare (TransportNetwork network, ProfileRequest request, PointSet destinations) {
        OnDemandEgressIndex index = network.onDemandEgressIndex();
        if (index.isEmpty()) {
            return null;
        }
        BitSet activeServices = network.transitLayer.getActiveServicesForDate(request.date);
        // A rider can alight from transit no earlier than the start of the departure window and
        // must complete the whole trip within the maximum duration of the latest departure.
        int beginTime = request.fromTime;
        int endTime = request.toTime + request.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE;
        TIntObjectMap<List<OnDemand>> servicesForStop = new TIntObjectHashMap<>();
        Map<OnDemand, OnDemandPlaceFilter> dropOffPlaces = new IdentityHashMap<>();
        Map<StreetMode, TIntSet> candidateStopsForMode = new EnumMap<>(StreetMode.class);
        index.stops().forEach(stop -> {
            List<OnDemand> usable = null;
            for (OnDemand od : index.servicesForStop(stop)) {
                if (od.canPickUpDuring(beginTime, endTime, activeServices)) {
                    if (usable == null) {
                        usable = new ArrayList<>();
                    }
                    usable.add(od);
                    candidateStopsForMode.computeIfAbsent(od.streetMode, mode -> new TIntHashSet()).add(stop);
                    dropOffPlaces.computeIfAbsent(od, o -> OnDemandPlaceFilter.dropOff(o, network));
                }
            }
            if (usable != null) {
                servicesForStop.put(stop, usable);
            }
            return true;
        });
        if (servicesForStop.isEmpty()) {
            return null;
        }
        // Fetch linkages for all modes used by at least one candidate service.
        Map<StreetMode, LinkedPointSet> linkageForMode = new EnumMap<>(StreetMode.class);
        candidateStopsForMode.forEach((mode, candidateStops) -> {
            LinkedPointSet linkage = network.linkageCache.getLinkage(destinations, network.streetLayer, mode);
            // The NetworkPreloader normally builds all rows asynchronously before any search runs, so ensureStops is a
            // cheap verification and a fallback for tests or freeform regional work that do not use the preloader.
            linkage.getOnDemandEgressTable().ensureStops(candidateStops);
            linkageForMode.put(mode, linkage);
        });
        return new OnDemandEgress(servicesForStop, dropOffPlaces, linkageForMode, request);
    }

    /// Return the street modes of all candidate services. There is one cost table for each of these modes.
    public Set<StreetMode> vehicleModes () {
        return linkageForMode.keySet();
    }

    /// Returns on-demand egress table entries for the given street mode and target as packed
    /// triples of (stop, walk millimeters, unscaled ride seconds), or null when the target cannot
    /// be reached by any on-demand service using that mode. May contain stops with no candidate
    /// service for this request (rows computed for other requests) which will be filtered by
    /// servicesForStop. Entries should only be applied to services with the given mode.
    /// The returned array must not be modified.
    public int[] ridesForTarget (StreetMode vehicleMode, int target) {
        return linkageForMode.get(vehicleMode).getOnDemandEgressTable().ridesForPoint(target);
    }

    /// Returns the candidate services picking up alighting riders at the given stop for this
    /// request, on any vehicle mode, or null when there are none.
    public List<OnDemand> servicesForStop (int stop) {
        return servicesForStop.get(stop);
    }

    /// Returns true when the given service's drop-off place accepts the given target point.
    /// The point is tested where it is linked to a street allowing the service's mode.
    public boolean dropOffAccepts (OnDemand od, int target) {
        LinkedPointSet linkage = linkageForMode.get(od.streetMode);
        return linkage.pointWithinPlace(target, dropOffPlaces.get(od), edgeScratch, splitScratch);
    }

    /// Convert a stored walk distance to a duration using this request's walk speed.
    public int walkSeconds (int walkMillimeters) {
        return walkMillimeters / walkSpeedMillimetersPerSecond;
    }

    /// Convert the unscaled ride seconds stored in an egress table to the duration of a ride on the
    /// given service for this request, applying the service's duration factor. For a BICYCLE
    /// service the stored seconds are also converted from the reference bicycle speed to this
    /// request's bike speed. That conversion is exact for the part of the path where the bicycle is
    /// ridden. Where the stored path walks the bicycle along a street that does not allow bicycles,
    /// it is an approximation. These factors do not apply to the walk to reach the vehicle. The
    /// access leg applies the duration factor inside the street search, rounding once per
    /// traversal. Here it is applied to a whole cached path, rounding once. The difference is well
    /// under a second, an acceptable approximation.
    public int rideSeconds (OnDemand od, int unscaledRideSeconds) {
        double scale = od.durationFactor;
        if (od.streetMode == StreetMode.BICYCLE) {
            scale *= bicycleRideScale;
        }
        return (int) Math.round(scale * unscaledRideSeconds);
    }

}
