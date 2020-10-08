package com.conveyal.gtfs.util;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * Convenience methods and shared factory instances for working with JTS geometries.
 */
public class GeometryUtil {

    public static final GeometryFactory geometryFactory = new GeometryFactory();

    public static final com.vividsolutions.jts.geom.GeometryFactory legacyGeometryFactory =
            new com.vividsolutions.jts.geom.GeometryFactory();

    /**
     * Convert a new-style JTS LineString into an old one, generally for compatibility with old serialized data.
     *
     * @param lineString a new JTS LineString object from the org.locationtech package.
     * @return an old-style JTS LineString object from the com.vividsolutions package.
     */
    public static com.vividsolutions.jts.geom.LineString toLegacyLineString (LineString lineString) {
        if (lineString == null) {
            return null;
        } else {
            com.vividsolutions.jts.geom.Coordinate[] legacyCoordinates =
                    new com.vividsolutions.jts.geom.Coordinate[lineString.getNumPoints()];
            for (int c = 0; c < lineString.getNumPoints(); c++) {
                Coordinate coordinate = lineString.getCoordinateN(c);
                legacyCoordinates[c] = new com.vividsolutions.jts.geom.Coordinate(coordinate.x, coordinate.y);
            }
            return legacyGeometryFactory.createLineString(legacyCoordinates);
        }
    }

}
