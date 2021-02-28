package com.conveyal.analysis.models;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Adjust the speed of a route.
 */
public class AdjustSpeed extends Modification {
    public String getType() {
        return "adjust-speed";
    }

    public String feed;

    public String[] routes;

    /** At least one trip from each pattern to modify. Does not select single trips, only whole patterns. */
    public String[] trips;

    /** array of [from stop, to stop] specifying single hops this should be applied to */
    public String[][] hops;

    /** the factor by which to scale speed. 1 means no change, 2 means faster. */
    public double scale;

    public com.conveyal.r5.analyst.scenario.AdjustSpeed toR5 () {
        com.conveyal.r5.analyst.scenario.AdjustSpeed as = new com.conveyal.r5.analyst.scenario.AdjustSpeed();
        as.comment = name;

        if (trips == null) {
            as.routes = feedScopedIdSet(feed, routes);
        } else {
            as.patterns = feedScopedIdSet(feed, trips);
        }

        if (hops != null) {
            as.hops = new ArrayList<>(hops.length);
            for (String[] hopStops : hops) {
                as.hops.add(feedScopedIdArray(feed, hopStops));
            }
        }
        as.scale = scale;

        return as;
    }
}
