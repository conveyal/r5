package com.conveyal.gtfs.geom;

/// Base class for geometries whose representation is a single packed array of coordinates.
/// CLineString and CPolygon are siblings under this type rather than a chain of subtypes, allowing
/// each class's toJts method to return the narrowest equivalent JTS type.
public abstract class CPackedCoords implements CGeometry {

    /// A packed array of N double-precision coordinates (x, y) which is to say (lon, lat).
    /// Should always have an even number of elements.
    protected final double[] packedCoords;

    protected CPackedCoords (double[] packedCoords) {
        if (packedCoords.length < 4) {
            throw new IllegalArgumentException("Line requires at least two points.");
        }
        if ((packedCoords.length % 2) != 0) {
            throw new IllegalArgumentException("Packed coordinate array must be of even length.");
        }
        this.packedCoords = packedCoords;
    }

    public int nPoints () {
        return packedCoords.length / 2;
    }

    public double getLon (int c) {
        return this.packedCoords[c*2];
    }

    public double getLat (int c) {
        return this.packedCoords[c*2+1];
    }

    @Override
    public CBox toBox () {
        return toBox(this);
    }

    /// Make a bounding box for one or more packed-coordinate geometries.
    /// Can be used with variadic parameters, or by directly passing arrays.
    /// Note that this will also work for polygons with holes, as their _outer_ ring is the
    /// packedCoords field inherited from this class.
    public static CBox toBox (CPackedCoords... items) {
        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        for (CPackedCoords item : items) {
            for (int i = 0; i < item.packedCoords.length; i += 2) {
                double lon = item.packedCoords[i];
                double lat = item.packedCoords[i + 1];
                if (lon < minLon) minLon = lon;
                if (lon > maxLon) maxLon = lon;
                if (lat < minLat) minLat = lat;
                if (lat > maxLat) maxLat = lat;
            }
        }
        return new CBox(minLon, minLat, maxLon, maxLat);
    }

}
