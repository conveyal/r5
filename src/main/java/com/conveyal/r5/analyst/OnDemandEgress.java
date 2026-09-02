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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.r5.profile.PerTargetPropagater.MM_PER_METER;

/// Everything one request needs to evaluate on-demand egress legs during propagation.
/// It holds the services that can pick up an alighting rider at each stop, restricted to those
/// running on the request date with plausibly overlapping time windows, as well as one drop-off
/// place filter per candidate service and a car cost table with rows for all candidate stops.
///
/// This is the request-dependent, inexpensive half of on-demand egress and is prepared once before
/// the per-target loops. The expensive request-independent half is the car cost table.
///
/// Instances are not thread-safe. Place filters and the containment scratch objects are reused
/// across calls. The propagator processes targets sequentially in one thread.
public class OnDemandEgress {

    private final TIntObjectMap<List<OnDemand>> servicesForStop;

    private final Map<OnDemand, OnDemandPlaceFilter> dropOffPlaces;

    private final OnDemandEgressTable table;

    private final LinkedPointSet carLinkage;

    /// This request's walk speed in millimeters per second,
    /// applied to the walk distances stored in the egress table.
    private final int walkSpeedMillimetersPerSecond;

    /// Scratch objects for place filter containment tests, reused across points.
    private final EdgeStore.Edge edgeScratch;
    private final Split splitScratch = new Split();

    private OnDemandEgress (
            TIntObjectMap<List<OnDemand>> servicesForStop,
            Map<OnDemand, OnDemandPlaceFilter> dropOffPlaces,
            OnDemandEgressTable table,
            LinkedPointSet carLinkage,
            int walkSpeedMillimetersPerSecond
    ) {
        this.servicesForStop = servicesForStop;
        this.dropOffPlaces = dropOffPlaces;
        this.table = table;
        this.carLinkage = carLinkage;
        this.walkSpeedMillimetersPerSecond = walkSpeedMillimetersPerSecond;
        this.edgeScratch = carLinkage.streetLayer.edgeStore.getCursor();
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
        TIntSet candidateStops = new TIntHashSet();
        index.stops().forEach(stop -> {
            List<OnDemand> usable = null;
            for (OnDemand od : index.servicesForStop(stop)) {
                if (od.canPickUpDuring(beginTime, endTime, activeServices)) {
                    if (usable == null) {
                        usable = new ArrayList<>();
                    }
                    usable.add(od);
                    dropOffPlaces.computeIfAbsent(od, o -> OnDemandPlaceFilter.dropOff(o, network));
                }
            }
            if (usable != null) {
                servicesForStop.put(stop, usable);
                candidateStops.add(stop);
            }
            return true;
        });
        if (candidateStops.isEmpty()) {
            return null;
        }
        LinkedPointSet carLinkage = network.linkageCache.getLinkage(destinations, network.streetLayer, StreetMode.CAR);
        OnDemandEgressTable table = carLinkage.getOnDemandEgressTable();
        // The NetworkPreloader normally builds all rows asynchronously before any search runs, so ensureStops is a
        // cheap verification and a fallback for tests or freeform regional work that do not use the preloader.
        table.ensureStops(candidateStops);
        int walkSpeedMillimetersPerSecond = (int) (request.walkSpeed * MM_PER_METER);
        return new OnDemandEgress(servicesForStop, dropOffPlaces, table, carLinkage, walkSpeedMillimetersPerSecond);
    }

    /// Returns the on-demand rides reaching the given target as packed triples of (stop, walk
    /// millimeters, unscaled car seconds), or null when no on-demand pick-up stop reaches the
    /// target. May contain stops with no candidate service for this request (rows computed for
    /// other requests) which will be filtered by servicesForStop.
    /// The returned array must not be modified.
    public int[] ridesForTarget (int target) {
        return table.ridesForPoint(target);
    }

    /// Convert a stored walk distance to a duration using this request's walk speed.
    public int walkSeconds (int walkMillimeters) {
        return walkMillimeters / walkSpeedMillimetersPerSecond;
    }

    /// Returns the candidate services picking up alighting riders at the given stop for this
    /// request, or null when there are none.
    public List<OnDemand> servicesForStop (int stop) {
        return servicesForStop.get(stop);
    }

    /// Returns true when the given service's drop-off place accepts the given target point.
    public boolean dropOffAccepts (OnDemand od, int target) {
        return carLinkage.pointWithinPlace(target, dropOffPlaces.get(od), edgeScratch, splitScratch);
    }

    /// Scale the duration of a ride by the service's duration factor.
    /// The factor applies to only the car portion alone.
    /// The walk to the vehicle is converted separately by walkSeconds.
    /// The access leg applies the factor inside the street search, rounding once per traversal.
    /// Here it is applied to a whole cached path, rounding once.
    /// The difference is well under a second, an acceptable approximation.
    public int rideSeconds (OnDemand od, int unscaledCarSeconds) {
        return (int) Math.round(od.durationFactor * unscaledCarSeconds);
    }

}
