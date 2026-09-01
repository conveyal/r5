package com.conveyal.gtfs.geom;

import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

/// Several polygons that are considered to form a single entity together. For example, an on-demand
/// pick-up zone with two disjoint parts on either side of an inaccessible area. Currently
/// CMultiPolygon must genuinely contain more than one polygon, and the constructor will reject
/// single-polygon arrays. Callers will need to unwrap single-member multipolygons and instead
/// create equivalent CPolygons.
public class CMultiPolygon implements CPolygonal {

    final CPolygon[] polygons;

    public CMultiPolygon (CPolygon[] polygons) {
        if (polygons.length < 2) {
            throw new IllegalArgumentException("A MultiPolygon must contain at least two polygons.");
        }
        this.polygons = polygons;
    }

    public CPolygon[] getPolygons () {
        return polygons;
    }

    @Override
    public CBox toBox () {
        return CPackedCoords.toBox(polygons);
    }

    @Override
    public MultiPolygon toJts () {
        Polygon[] jtsPolygons = new Polygon[polygons.length];
        for (int i = 0; i < jtsPolygons.length; i++) {
            jtsPolygons[i] = polygons[i].toJts();
        }
        return JTSConverter.GEOMETRY_FACTORY.createMultiPolygon(jtsPolygons);
    }

}
