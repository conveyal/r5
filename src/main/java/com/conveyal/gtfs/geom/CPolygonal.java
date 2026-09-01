package com.conveyal.gtfs.geom;

import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Polygonal;

/// Interface for both single polygons and multipolygons, allowing a mixture of both to be
/// used in the same place. Both implementations have similar methods for geometric containment.
public interface CPolygonal extends CGeometry {

    /// Convert a JTS multipolygon to compact form.
    /// A multipolygon containing only one polygon is unwrapped to a CPolygon.
    static CPolygonal fromJts (MultiPolygon jtsMultiPolygon) {
        CPolygon[] polygons = new CPolygon[jtsMultiPolygon.getNumGeometries()];
        for (int i = 0; i < polygons.length; i++) {
            polygons[i] = CPolygon.fromJts((Polygon) jtsMultiPolygon.getGeometryN(i));
        }
        return polygons.length == 1 ? polygons[0] : new CMultiPolygon(polygons);
    }

    static CPolygonal fromJts (Polygonal jtsPolygonal) {
        return switch (jtsPolygonal) {
            case Polygon p -> CPolygon.fromJts(p);
            case MultiPolygon mp -> fromJts(mp);
            case null, default ->
                  throw new IllegalArgumentException("Only polygon and multipolygon are supported.");
        };
    }

}
