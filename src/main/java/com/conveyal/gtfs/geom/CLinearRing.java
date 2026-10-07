package com.conveyal.gtfs.geom;

/// From GeoJSON spec section 3.1.6. (Polygon):
/// A linear ring is a closed LineString with four or more positions.
/// The first and last positions are equivalent, and they MUST contain
/// identical values; their representation SHOULD also be identical.
public class CLinearRing extends CLineString {
    public CLinearRing (double[] packedCoords) {
        super(packedCoords);
        int n = packedCoords.length;
        if (n < 4) {
            throw new IllegalArgumentException("A LinearRing must have at least four coordinates.");
        }
        if (packedCoords[0] != packedCoords[n - 2] || packedCoords[1] != packedCoords[n - 1]) {
            throw new IllegalArgumentException("First and last coordinate of ring must be identical.");
        }
    }
}
