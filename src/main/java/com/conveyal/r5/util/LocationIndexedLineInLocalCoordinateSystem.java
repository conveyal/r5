package com.conveyal.r5.util;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.linearref.LinearLocation;
import org.locationtech.jts.linearref.LocationIndexedLine;

import java.util.stream.Stream;

/**
 * Wraps a location indexed line so that the supplied operations (project, extractPoint, etc.) correctly account
 * for lines of longitude getting closer together toward the poles, even though the locationIndexedLine is still
 * constructed using geographic (unprojected) coordinates.
 *
 * It just projects the coordinates when the object is constructed, and de-projects them when you get a result out.
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
