package com.conveyal.osmlib;

import java.io.Serializable;

public class Node extends OSMEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    // PBF uses 100 nanodegrees (1e7) by default. (180 * 10^7) / (2^31) ~= 0.84 so we should be fine.
    private static final double FIXED_PRECISION_FACTOR = 1e7;

    public Node () { }

    public Node (double lat, double lon) {
        setLatLon(lat, lon);
    }

    /* Angles are stored as fixed precision 32 bit integers because 32 bit floats are not sufficiently precise. */
    public int fixedLat;
    public int fixedLon;

    public double getLat() {return fixedLat / FIXED_PRECISION_FACTOR;}

    public double getLon() {return fixedLon / FIXED_PRECISION_FACTOR;}

    public void setLatLon (double lat, double lon) {
        this.fixedLat = (int)(lat * FIXED_PRECISION_FACTOR);
        this.fixedLon = (int)(lon * FIXED_PRECISION_FACTOR);
    }

    @Override
    public Type getType() {
        return Type.NODE;
    }

    public String toString() {
        return "[Node "+getLat()+" "+getLon()+"]";
    }

    @Override
    public boolean equals(Object other) {
        if ( ! (other instanceof Node)) return false;
        Node otherNode = (Node) other;
        return this.fixedLat == otherNode.fixedLat &&
               this.fixedLon == otherNode.fixedLon &&
               this.tagsEqual(otherNode);
    }
}
