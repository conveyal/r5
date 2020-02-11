package com.conveyal.r5.point_to_point.builder;

import org.locationtech.jts.geom.Envelope;

/**
 * Information about router
 *
 * Currently only envelope and name
 */
public class RouterInfo {

    public String name = "default";
    public Envelope envelope;
}
