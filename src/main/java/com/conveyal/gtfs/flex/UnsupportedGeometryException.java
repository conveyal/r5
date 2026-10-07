package com.conveyal.gtfs.flex;

/// Thrown while streaming a GeoJSON geometry that may be valid GeoJSON but we do not support.
public class UnsupportedGeometryException extends RuntimeException {
    public UnsupportedGeometryException (String message) {
        super(message);
    }
}
