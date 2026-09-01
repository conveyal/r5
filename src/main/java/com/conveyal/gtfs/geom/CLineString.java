package com.conveyal.gtfs.geom;

import org.locationtech.jts.geom.LineString;

/// A sequence of two or more positions connected by straight line segments.
public class CLineString extends CPackedCoords {

    public CLineString (double[] packedCoords) {
        super(packedCoords);
    }

    @Override
    public LineString toJts () {
        return JTSConverter.GEOMETRY_FACTORY.createLineString(JTSConverter.toJts(packedCoords));
    }

}
