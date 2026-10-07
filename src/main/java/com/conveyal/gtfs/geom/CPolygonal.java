package com.conveyal.gtfs.geom;

import java.io.Serializable;

/// Marker interface for all polygonal geometries (Java does not have multiple inheritance)
/// Need to consider Polygon, MultiPolygon, and PolygonWithHoles. All should implement serializable.
public interface CPolygonal extends CGeometry {

}
