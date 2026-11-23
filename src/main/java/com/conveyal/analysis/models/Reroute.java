package com.conveyal.analysis.models;

import java.util.List;

/// Model object for a modification that alters the course of an existing route,
/// as represented by the UI.
public class Reroute extends Modification {
    public String getType() {
        return "reroute";
    }

    /// The _id of the gtfs feed, providing a scope for any unscoped identifiers in this Modification.
    public String feed;
    public String[] routes;
    public String[] trips;

    public String fromStop;
    public String toStop;

    public List<Segment> segments;

    /// Speed of the adjusted segment in km/h, per segment.
    /// Fractional because the UI derives them by floating point division from travel times.
    public double[] segmentSpeeds;

    /// Default dwell time, seconds
    public int dwellTime;

    /// Dwell times at adjusted stops, seconds
    /// using Integer not int because Integers can be null
    public Integer[] dwellTimes;

    public com.conveyal.r5.analyst.scenario.Reroute toR5 () {
        com.conveyal.r5.analyst.scenario.Reroute rr = new com.conveyal.r5.analyst.scenario.Reroute();
        rr.comment = name;

        List<ModificationStop> stops = ModificationStop.getStopsFromSegments(segments, dwellTimes, dwellTime, segmentSpeeds);
        rr.dwellTimes = ModificationStop.getDwellTimes(stops);
        rr.hopTimes = ModificationStop.getHopTimes(stops);
        rr.stops = ModificationStop.toStopSpecs(stops);

        if (this.trips == null) {
            rr.routes = feedScopedIdSet(feed, routes);
        } else {
            rr.patterns = feedScopedIdSet(feed, trips);
        }

        if (fromStop != null) {
            rr.fromStop = feedScopeId(feed, fromStop);
            rr.stops.remove(0);
        }

        if (toStop != null) {
            rr.toStop = feedScopeId(feed, toStop);
            rr.stops.remove(rr.stops.size() - 1);
        }

        return rr;
    }
}
