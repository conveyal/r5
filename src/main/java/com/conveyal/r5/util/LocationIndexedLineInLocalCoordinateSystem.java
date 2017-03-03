package com.conveyal.r5.util;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.linearref.LinearLocation;
import com.vividsolutions.jts.linearref.LocationIndexedLine;

import java.util.stream.Stream;

/**
 * Wraps a location indexed line in a local coordinate system, so that snapping works right.
 */
public class LocationIndexedLineInLocalCoordinateSystem {

    public final double xScale;

    private static final GeometryFactory geometryFactory = new GeometryFactory();

    private LocationIndexedLine line;

    public LocationIndexedLineInLocalCoordinateSystem(Coordinate[] coords) {
        if (coords.length == 0) throw new IllegalArgumentException("empty geometry!");

        xScale = Math.abs(Math.cos(Math.toRadians(coords[0].y)));

        coords = Stream.of(coords).map(this::projectGeographicCoordinate).toArray(i -> new Coordinate[i]);

        line = new LocationIndexedLine(geometryFactory.createLineString(coords));
    }


    public LinearLocation project(Coordinate coord) {
        coord = projectGeographicCoordinate(coord);
        // linear locations still valid in unprojected coordinate system.
        return line.project(coord);
    }

    public Coordinate extractPoint(LinearLocation loc) {
        return unprojectPoint(line.extractPoint(loc));
    }

    private Coordinate unprojectPoint(Coordinate coord) {
        return new Coordinate(coord.x * xScale, coord.y);
    }

    private Coordinate projectGeographicCoordinate (Coordinate coord) {
        return new Coordinate(coord.x / xScale, coord.y);
    }
}
