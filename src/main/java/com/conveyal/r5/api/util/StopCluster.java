package com.conveyal.r5.api.util;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Groups stops by geographic proximity and name similarity.
 * This will at least half the number of distinct stop places. In profile routing this means a lot less branching
 * and a lot less transfers to consider.
 *
 * It seems to work quite well for both the Washington DC region and Portland. Locations outside the US would require
 * additional stop name normalizer modules.
 */
public class StopCluster {

    private static final Logger LOG = LoggerFactory.getLogger(StopCluster.class);

    //Internal ID of stop cluster @notnull
    public final String id;
    //Name of first stop in a cluster @notnull
    public final String name;
    public float lon;
    public float lat;
    //Stops in a cluster
    public final List<Stop> stops = Lists.newArrayList();

    public StopCluster(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public StopCluster() {
        this.id = "UNKNOWN";
        this.name = "EMPTY";
    }

    public void computeCenter() {
        float lonSum = 0, latSum = 0;
        for (Stop stop : stops) {
            lonSum += stop.lon;
            latSum += stop.lat;
        }
        lon = lonSum / stops.size();
        lat = latSum / stops.size();
    }

    @Override
    public String toString() {
        return name;
    }

}

