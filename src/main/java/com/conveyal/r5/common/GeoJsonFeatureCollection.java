package com.conveyal.r5.common;

import java.util.Collection;

/**
 * Deserialize a GeoJSON FeatureCollection
 */
public class GeoJsonFeatureCollection {
    public String type = "FeatureCollection";
    public Collection<GeoJsonFeature> features;
}
