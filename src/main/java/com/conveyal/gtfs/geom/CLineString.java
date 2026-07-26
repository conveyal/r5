package com.conveyal.gtfs.geom;

/// See flex design document for rationale.
/// Reimplementation of to streamline memory use and serialization.
/// The prefix C maintains distinction from JTS geometries. It stands for Conveyal and Compact.
/// They are designed to work well with built-in and generic serialization systems.
/// They should contain as few references as is reasonably possible, favoring packed arrays of
/// primitive types. Object graphs should be tree-like and contain no shared references.
/// It only supports 2D coordinates which are assumed to be in WGS84 degrees.
/// Using double-precision floats for simplicity now but could conceivably use fixed ints.
public class CLineString implements CGeometry {
    /// A packed array of N double-precision coordinates (x, y) that is (lon, lat)
    /// Should always have an even number of elements.
    protected final double[] packedCoords;

    public CLineString (double[] packedCoords) {
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
        return this.packedCoords[c/2];
    }

    public double getLat (int c) {
        return this.packedCoords[c/2+1];
    }

    public CBox toBox () {
        return toBox(this);
    }

    /// Make a bounding box for one or more lineStrings or their subtypes.
    /// Can be used with variadic parameters, or by directly passing arrays.
    public static CBox toBox (CLineString... lineStrings) {
        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        for (CLineString ls : lineStrings) {
            for (int i = 0; i < ls.packedCoords.length; i += 2) {
                double lon = ls.packedCoords[i];
                double lat = ls.packedCoords[i + 1];
                if (lon < minLon) minLon = lon;
                if (lon > maxLon) maxLon = lon;
                if (lat < minLat) minLat = lat;
                if (lat > maxLat) maxLat = lat;
            }

        }
        return new CBox(minLon, minLat, maxLon, maxLat);
    }

}
