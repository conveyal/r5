package com.conveyal.r5.common;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.util.HashMap;
import java.util.Map;

/**
 * GeoJSON Feature class
 *
 * Written because GeoJSON Feature class from geojson-jackson has different type of Geometry
 * and needs a conversion
 *
 * Currently only used in TransportNetworkVisualizer
 */
public class GeoJsonFeature {
    //for serialization
    private final String type = "Feature";
    private Map<String, Object> properties;

    private Geometry geometry;

    public GeoJsonFeature(Geometry geometry) {
        this.geometry = geometry;
        this.properties = new HashMap<>(5);
    }

    public GeoJsonFeature(double lon, double lat) {
        this(GeometryUtils.geometryFactory.createPoint(new Coordinate(lon, lat)));
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    public void addProperty(String propertyName, Object propertyValue) {
        properties.put(propertyName, propertyValue);
    }
}

