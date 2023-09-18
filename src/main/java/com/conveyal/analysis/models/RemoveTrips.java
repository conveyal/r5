package com.conveyal.analysis.models;

import org.bson.codecs.pojo.annotations.BsonDiscriminator;

import java.util.List;

/**
 * Remove trips from a graph.
 */
@BsonDiscriminator(key = "type", value = "remove-trips")
public class RemoveTrips extends Modification {
    public String getType() {
        return "remove-trips";
    }

    public String feed;

    public List<String> routes;

    public List<String> patterns;

    public List<String> trips;

    public com.conveyal.r5.analyst.scenario.RemoveTrips toR5 () {
        com.conveyal.r5.analyst.scenario.RemoveTrips rt = new com.conveyal.r5.analyst.scenario.RemoveTrips();
        rt.comment = name;

        if (trips == null) {
            rt.routes = feedScopedIdSet(feed, routes);
        } else {
            rt.patterns = feedScopedIdSet(feed, trips);
        }

        return rt;
    }
}
