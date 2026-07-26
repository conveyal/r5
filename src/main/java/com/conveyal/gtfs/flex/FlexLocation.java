package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.geom.CPolygon;
import com.conveyal.gtfs.geom.CPolygonal;

import java.io.Serializable;

/// Model object corresponding to features in the GTFS locations.geojson file:
/// "zones where riders can request either pickup or drop off by on-demand services".
/// There may be problems serializing JTS Geometries to MapDB as they reference GeometryFactory.
/// See the gtfs-lib Shape model class for an alternative approach of construction on the fly.
public class FlexLocation implements Serializable {

    /// Unique identifier for this location (flex pick-up or drop-off zone).
    public String id;

    /// The name of the location as displayed to riders.
    public String stopName;

    /// A description of the location to help orient riders.
    public String stopDesc;

    /// Constrained to be Polygon or MultiPolygon by the GTFS Flex spec.
    /// Currently only supporting Polygon until our geometry class hierarchy is more complete.
    public CPolygon geometry;

    public FlexLocation (String id, String stopName, String stopDesc, CPolygon geometry) {
        this.id = id;
        this.stopName = stopName;
        this.stopDesc = stopDesc;
        this.geometry = geometry;
    }

}
