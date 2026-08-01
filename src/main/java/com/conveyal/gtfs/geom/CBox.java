package com.conveyal.gtfs.geom;

/// Could be a record.
public class CBox {
    final double minLon;
    final double minLat;
    final double maxLon;
    final double maxLat;

    public CBox (double minLon, double minLat, double maxLon, double maxLat) {
        if (maxLat <= minLat || maxLon <= minLon) {
            throw new IllegalArgumentException("Max must be higher than min.");
        }
        this.minLon = minLon;
        this.minLat = minLat;
        this.maxLon = maxLon;
        this.maxLat = maxLat;
    }

}
