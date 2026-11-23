package com.conveyal.gtfs.geom;

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
