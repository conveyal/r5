package com.conveyal.r5.analyst.scenario.ondemand;

import gnu.trove.set.TIntSet;
import org.locationtech.jts.geom.Geometry;

public abstract class OnDemandService {

    /**
     * The amount of time you have to wait (in seconds) before service starts. Zero if you can start travel on this leg
     * immediately (e.g. a taxi stand outside a station), -1 if no service is available at all.
     */
    public int waitTimeSeconds;

    /**
     * Stops covered by this service.
     * Interpretation varies depending on whether this service is for access to or egress from transit.
     * If we eventually want to reflect multiple services with different waits, we could
     * instead return a TIntIntMap from allowed stop indexes to wait times.
     */
    public TIntSet stops;

    /**
     * The geographic area covered by this service. If null, no geographic restriction applies.
     * Interpretation varies depending on whether this service is for access to or egress from transit.
     * In floating point WGS84 (lon, lat) coordinates. Should be a polygon or multipolygon.
     */
    public Geometry serviceArea;

}
