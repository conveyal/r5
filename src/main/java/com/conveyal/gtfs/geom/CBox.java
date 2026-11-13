package com.conveyal.gtfs.geom;

/// A geographic bounding box, typically for for a CGeometry instance.
/// A CBox may have zero extent in either dimension. For example, the bounding box of a point is
/// also a point, and the bounding box of a line parallel to an axis is a line a segment. Therefore
/// validation will accept equal min and max values, but will reject `max < min` as a caller error.
/// A zero-area polygon can have a normal looking bounding box (when it is not axis-aligned), so
/// we don't try to catch anything more subtle here. This could potentially be made into a record.
public class CBox {
    final double minLon;
    final double minLat;
    final double maxLon;
    final double maxLat;

    public CBox (double minLon, double minLat, double maxLon, double maxLat) {
        if (maxLat < minLat || maxLon < minLon) {
            throw new IllegalArgumentException("Max must not be lower than min.");
        }
        this.minLon = minLon;
        this.minLat = minLat;
        this.maxLon = maxLon;
        this.maxLat = maxLat;
    }

}
