package com.conveyal.analysis.models;

import org.locationtech.jts.geom.Envelope;

/**
 * Represents a bounding box in degrees in the HTTP API.
 */
public class Bounds {

    /** The latitude of the north edge and south edge, the longitude of the east edge and west edge of the box. */
    public double north, east, south, west;

    @Override
    public boolean equals (Object other) {
        return equals(other, 0D);
    }

    public boolean equals (Object other, double tolerance) {
        if (!Bounds.class.isInstance(other)) return false;
        Bounds o = (Bounds) other;
        return Math.abs(north - o.north) <= tolerance && Math.abs(east - o.east) <= tolerance &&
                Math.abs(south - o.south) <= tolerance && Math.abs(west - o.west) <= tolerance;
    }

    public Envelope envelope () {
        return new Envelope(this.west, this.east, this.south, this.north);
    }

}
