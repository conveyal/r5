package com.conveyal.r5.analyst.scenario.ondemand;

import gnu.trove.set.TIntSet;
import org.locationtech.jts.geom.Geometry;

/**
 * Represents an on-demand service available from certain stops. If serviceArea is specified, passengers can ride
 * from these stops to destinations in the serviceArea. If serviceArea is null, passengers can ride from these stops
 * to any destination (up to the egress cost table limit).
 */
public class EgressService extends OnDemandService{

    public EgressService (int waitTimeSeconds, TIntSet egressStops, Geometry serviceArea) {
        if (waitTimeSeconds < 0) throw new AssertionError("Wait times should always be non-negative.");
        this.waitTimeSeconds = waitTimeSeconds;
        this.stops = egressStops;
        this.serviceArea = serviceArea;
    }
}
