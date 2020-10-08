package com.conveyal.analysis.models;

/**
 * Created by matthewc on 3/2/16.
 */
public class RemoveStops extends Modification {
    public String getType() {
        return "remove-stops";
    }

    public String feed;

    public String[] routes;

    public String[] trips;

    public String[] stops;

    public int secondsSavedAtEachStop = 0;

    public com.conveyal.r5.analyst.scenario.RemoveStops toR5 () {
        com.conveyal.r5.analyst.scenario.RemoveStops rs = new com.conveyal.r5.analyst.scenario.RemoveStops();
        rs.comment = name;
        rs.stops = feedScopeIds(feed, stops);

        if (trips == null) {
            rs.routes = feedScopeIds(feed, routes);
        } else {
            rs.patterns = feedScopeIds(feed, trips);
        }

        rs.secondsSavedAtEachStop = secondsSavedAtEachStop;

        return rs;
    }
}
