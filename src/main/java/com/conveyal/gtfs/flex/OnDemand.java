package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.geom.CPolygon;

import java.io.Serializable;
import java.util.BitSet;

/// Our internal TransportNetwork representation of an on-demand transit service. Currently these
/// connect one polygonal zone or set of pointlike stops to one other such zone or set. Instances of
/// this class are serialized into the TransportNetwork, so polygons are stored as our own CPolygon
/// which serializes as plain coordinate arrays without the shared GeometryFactory/PrecisionModel
/// references found in JTS objects.
public class OnDemand implements Serializable {

    public String id;
    public String name;

    public CPolygon fromPolygon;
    public CPolygon toPolygon;

    // TIntSet forces use of nonstandard bool lambda functions and final variables for iteration.
    // We demote them to arrays before use. These will be null if the service does not specify them.
    // At routing time these stop sets act through their meeting areas (see MeetingAreas and
    // OnDemandPlaceFilter), which are derived lazily per network rather than stored here.

    public int[] fromStopIndexes;
    public int[] toStopIndexes;
    public String serviceId;
    public int serviceCode;

    /// Constant added to travel time to represent waiting for and boarding a vehicle,
    /// loaded from GTFS safe_duration_offset. Units are seconds.
    public double durationOffset;
    public double durationFactor;

    // Time windows are in seconds after midnight. Following the literature on flexible transit we
    // refer to the moment the rider is available to board (the end of any access walk) as the
    // "ready time". We check the pick-up window against this ready time plus the wait (delay)
    // defined for the service, awaiting the beginning of the window if that is later.
    // For the drop-off window, only the end of the window is stored. An operator who picked a
    // rider up will presumably drop them off even if they arrive before the published drop-off
    // window's start, as intentionally delaying arrival would serve no purpose.
    // A service that is always available (derived from GTFS lacking windows, or eventually a pick
    // up delay modification) is represented with a start time of 0 and an end time of MAX_VALUE.

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
        if (!serviceCodes.get(this.serviceCode)) return false;
        double earliestBoarding = Math.max(beginTime, fromWindowStart) + durationOffset;
        return earliestBoarding < fromWindowEnd
                && earliestBoarding <= endTime
                && earliestBoarding < toWindowEnd;
    }

}

