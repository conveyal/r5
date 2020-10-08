package com.conveyal.analysis.models;

import java.util.List;

/**
 * Created by matthewc on 3/28/16.
 */
public class Reroute extends Modification {
    public String getType() {
        return "reroute";
    }

    public String feed;
    public String[] routes;
    public String[] trips;

    public String fromStop;
    public String toStop;

    public List<Segment> segments;

    /** speed of the adjusted segment, km/h, per segment */
    public int[] segmentSpeeds;

    /** Default dwell time, seconds */
    public int dwellTime;

    /** Dwell times at adjusted stops, seconds */
    // using Integer not int because Integers can be null
    public Integer[] dwellTimes;

    public com.conveyal.r5.analyst.scenario.Reroute toR5 () {
        com.conveyal.r5.analyst.scenario.Reroute rr = new com.conveyal.r5.analyst.scenario.Reroute();
        rr.comment = name;

        List<ModificationStop> stops = ModificationStop.getStopsFromSegments(segments, dwellTimes, dwellTime, segmentSpeeds);
        rr.dwellTimes = ModificationStop.getDwellTimes(stops);
        rr.hopTimes = ModificationStop.getHopTimes(stops);
        rr.stops = ModificationStop.toStopSpecs(stops);

        if (this.trips == null) {
            rr.routes = feedScopeIds(feed, routes);
        } else {
            rr.patterns = feedScopeIds(feed, trips);
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
