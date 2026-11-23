package com.conveyal.gtfs.geom;

import org.locationtech.jts.geom.Point;

/// A single 2D point. Used only for the position of pointlike objects. Not used internally by
/// CLineStrings, CLinearRings, etc. which have packed primitive arrays rather than nested CPoints.
public record CPoint (double lon, double lat) implements CGeometry {

    @Override
    public CBox toBox () {
        return new CBox(lon, lat, lon, lat);
    }

    @Override
    public Point toJts () {
        return JTSConverter.pointAt(lon, lat);
    }

}
