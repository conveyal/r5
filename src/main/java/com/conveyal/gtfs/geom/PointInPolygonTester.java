package com.conveyal.gtfs.geom;

import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Polygon;

/// Allows efficiently performing repeated point-in-polygon checks against a single polygon without
/// creating a throwaway Point (and associated CoordinateSequence and Envelope instances) per check.
/// This wraps the IndexedPointInAreaLocator that JTS PreparedPolygon uses internally for point
/// queries. Preparatory work is done once on the first call, and subsequent tests should be much
/// faster. A bounding box check runs first so points nowhere near the polygon are rejected without
/// consulting the index, again similar to PreparedPolygon but without constructing Points.
///
/// Instances are NOT threadsafe because a single Coordinate instance is reused across calls.
/// Create one instance per search or thread.
public class PointInPolygonTester {

    private final IndexedPointInAreaLocator locator;

    private final Envelope envelope;

    private final Coordinate coordinate = new Coordinate();

    public PointInPolygonTester (Polygon polygon) {
        this.locator = new IndexedPointInAreaLocator(polygon);
        this.envelope = polygon.getEnvelopeInternal();
    }

    public PointInPolygonTester (CPolygon cPolygon) {
        this(JTSConverter.toJts(cPolygon));
    }

    /// Returns true if the given point is strictly inside the polygon.
    /// Points exactly on the boundary yield false, matching JTS contains() semantics.
    public boolean contains (double lon, double lat) {
        if (!envelope.covers(lon, lat)) {
            return false;
        }
        coordinate.x = lon;
        coordinate.y = lat;
        return locator.locate(coordinate) == Location.INTERIOR;
    }

}
