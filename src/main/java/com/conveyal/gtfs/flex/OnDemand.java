package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.geom.CPolygonal;

import java.io.Serializable;
import java.util.BitSet;

/// Our internal TransportNetwork representation of an on-demand transit service. Currently these
/// connect one polygonal zone or set of pointlike stops to one other such zone or set. Instances of
/// this class are serialized into the TransportNetwork, so areas are stored as our own compact
/// polygonal geometry, which serializes as plain coordinate arrays without the shared
/// GeometryFactory/PrecisionModel references found in JTS objects.
public class OnDemand implements Serializable {

    public String id;
    public String name;

    public CPolygonal fromPolygon;
    public CPolygonal toPolygon;

    // TIntSet forces use of nonstandard bool lambda functions and final variables for iteration.
    // We demote them to the arrays below before use. These will be null if the service does not
    // specify them. At routing time these stop sets act through their meeting areas (see MeetingAreas
    // and OnDemandPlaceFilter), which are derived lazily per network rather than stored here.

    public int[] fromStopIndexes;
    public int[] toStopIndexes;

    /// The GTFS service_id and internal service code of the calendar determining the dates on
    /// which this service runs. When alwaysActive is true, serviceId is null and serviceCode
    /// is -1 so any code reading them fails fast instead of silently matching a real service.
    public String serviceId;
    public int serviceCode = -1;

    /// True for services that run on every date (have no serviceId).
    /// Set only for services created by an AddOnDemand modification.
    /// Flex services loaded from GTFS should always have serviceCode like scheduled transit.
    public boolean alwaysActive;

    /// The amount of time one must wait to be picked up by this service, in units of seconds.
    /// Loaded from GTFS safe_duration_offset or from waitMinutes in an AddOnDemand modification.
    public double durationOffset;

    /// A multiplicative factor that scales ride duration relative to driving at the speed limit.
    /// This represents detours to serve other riders, as well as any slowdown due to the type of
    /// vehicle or congestion not represented in the OSM data. Loaded from safe_duration_factor in
    /// GTFS or from an AddOnDemand modification.
    public double durationFactor;

    // Time windows are in seconds after midnight. Following the literature on flexible transit we
    // refer to the moment the rider is available to board (the end of any access walk) as the
    // "ready time". We check the pick-up window against this ready time plus the wait (delay)
    // defined for the service, awaiting the beginning of the window if that is later.
    // For the drop-off window, only the end of the window is stored. An operator who picked a
    // rider up will presumably drop them off even if they arrive before the published drop-off
    // window's start, as intentionally delaying arrival would serve no purpose.
    // A service that is always available (derived from GTFS lacking windows, or from an
    // AddOnDemand modification) is represented with a start time of 0 and an end time of MAX_VALUE.

    public int fromWindowStart;
    public int fromWindowEnd;
    public int toWindowEnd;

    /// Inexpensively pre-filters OnDemand services, deliberately overselecting. The final test for
    /// whether the service will be used is applied to each initial state as the on-demand street
    /// search is initialized. Returns true when this OnDemand service may be usable for a rider
    /// who will board in the interval `[beginTime, endTime)`. The tightest conveniently available
    /// bounds are the beginning of the departure time window at the origin, and the end of the
    /// departure time window at the origin plus the maximum travel time for the whole trip.
    public boolean canPickUpDuring (int beginTime, int endTime, BitSet serviceCodes) {
        if (!alwaysActive && !serviceCodes.get(this.serviceCode)) return false;
        double earliestBoarding = Math.max(beginTime, fromWindowStart) + durationOffset;
        return earliestBoarding < fromWindowEnd
                && earliestBoarding <= endTime
                && earliestBoarding < toWindowEnd;
    }

    /// Returns the duration in seconds of an egress leg on this service, for a rider who alights
    /// from scheduled transit at the given clock time, walks the given number of seconds to a
    /// vehicle, then rides for the given number of seconds. Includes everything between alighting
    /// from transit and alighting from the on-demand vehicle Returns -1 when the service's time
    /// windows do not allow the trip.
    ///
    /// Access availability is evaluated for one representative rider departing at the middle of the
    /// departure window because the access search runs before any departure time is chosen. On the
    /// other hand, egress evaluation runs during propagation where the true arrival time at the
    /// stop is known for each departure time and Monte Carlo draw, so this test is exact per
    /// iteration.
    public int egressLegSeconds (int alightingClockTime, int walkSeconds, int rideSeconds) {
        int readyTime = alightingClockTime + walkSeconds;
        int boarding = (int) (Math.max(readyTime, fromWindowStart) + Math.round(durationOffset));
        if (boarding >= fromWindowEnd) return -1;
        int dropOff = boarding + rideSeconds;
        if (dropOff >= toWindowEnd) return -1;
        return dropOff - alightingClockTime;
    }

}

