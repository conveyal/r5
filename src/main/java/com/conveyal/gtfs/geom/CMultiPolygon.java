package com.conveyal.gtfs.geom;

public class CMultiPolygon implements CPolygonal {
    CPolygon[] polygons;
    public CMultiPolygon (CPolygon[] polygons) {
        this.polygons = polygons;
    }
}
