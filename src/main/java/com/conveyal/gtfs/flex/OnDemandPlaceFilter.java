package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.geom.CPolygon;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;

/// This is an interface for predicates that filter street search states, retaining only those
/// where a particular on-demand service will pick riders up or drop them off. We have separate
/// implementations for polygonal on-demand zones and GTFS "location groups" which are sets of one
/// or more GTFS stops (via "meeting areas").
///
/// This interface unifies stop-based services and polygon services, making stop-based services
/// behave like polygon services with inferred areas.
///
/// Filters are constructed per request through the static factory methods and are not
/// thread-safe because the implementations hold reusable flyweight cursor objects.
public interface OnDemandPlaceFilter {

    /// Returns true when the given vertex is accepted by this filter.
    boolean containsVertex (int vertexIndex);

    /// Returns true when a point off the street network is accepted by this filter.
    /// The point is given in floating-point WGS84 as it has no permanent vertex representation.
    /// This handles arbitrary origin points and analysis destination sample points.
    boolean containsPoint (double lat, double lon, Split split);

    /// Returns a set containing at least all edges whose end states could be accepted by this
    /// filter (from a spatial index envelope query, possibly overselecting), or null when no
    /// mechanism exists for producing candidates and every state must be examined individually.
    ///
    /// Clipping a large car search result to the interior of a comparatively small polygon is
    /// much faster given a set of candidate edges within the polygon's envelope. On the other
    /// hand, MeetingAreas are small sets of vertices for which exhaustive scans are cheap.
    TIntSet candidateEdges ();

    /// Returns a filter for the given service's pick-up place.
    static OnDemandPlaceFilter pickUp (OnDemand od, TransportNetwork network) {
        return of(od.fromPolygon, od.fromStopIndexes, network);
    }

    /// Returns a filter for the given service's drop-off place.
    static OnDemandPlaceFilter dropOff (OnDemand od, TransportNetwork network) {
        return of(od.toPolygon, od.toStopIndexes, network);
    }

    /// GTFS validation ensures every endpoint of every flex trip references exactly one
    /// polygonal zone or location group. If a polygon is present it takes precedence. If
    /// neither kind is present, return a filter containing nothing, making the service unusable.
    private static OnDemandPlaceFilter of (CPolygon polygon, int[] stopIndexes, TransportNetwork network) {
        if (polygon != null) {
            return new PolygonPlaceFilter(polygon, network.streetLayer);
        }
        if (stopIndexes != null) {
            return new MeetingAreaPlaceFilter(network.meetingAreas().unionForStops(stopIndexes));
        }
        return new MeetingAreaPlaceFilter(new TIntHashSet());
    }

}
