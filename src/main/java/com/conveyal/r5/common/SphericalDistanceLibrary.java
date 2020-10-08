package com.conveyal.r5.common;

import org.apache.commons.math3.util.FastMath;
import org.locationtech.jts.geom.Coordinate;

/**
 * Created by matthewc on 10/22/15.
 */
public class SphericalDistanceLibrary {

    // average of equatorial and meriodonal circumferences, https://en.wikipedia.org/wiki/Earth
    public static final double EARTH_CIRCUMFERENCE_KM = 40041.4385;
    public static final double EARTH_CIRCUMFERENCE_METERS = EARTH_CIRCUMFERENCE_KM * 1000;

    public static final double RADIUS_OF_EARTH_IN_KM = 6371.01;
    public static final double RADIUS_OF_EARTH_IN_M = RADIUS_OF_EARTH_IN_KM * 1000;

    // 1 / Max over-estimation error of approximated distance,
    // for delta lat/lon in given range so that equirectangular distance is always under estimation
    public static final double MAX_ERR_INV = 0.999462;

    /** Convert meters to degrees of latitude */
    public static double metersToDegreesLatitude (double meters) {
        return meters / EARTH_CIRCUMFERENCE_METERS * 360;
    }

    /** Convert meters to degrees of longitude at the specified latitiude */
    public static double metersToDegreesLongitude (double meters, double degreesLatitude) {
        double cosLat = FastMath.cos(FastMath.toRadians(degreesLatitude));
        return metersToDegreesLatitude(meters) / cosLat;
    }

    /**
     * Approximated, fast and under-estimated equirectangular distance between two points.
     * Correct only for small delta lat/lon
     * See: http://www.movable-type.co.uk/scripts/latlong.html
     */
    public static double fastDistance(Coordinate from, Coordinate to) {
        double dLat = FastMath.toRadians(to.y - from.y);
        double dLon = FastMath.toRadians(to.x - from.x) * FastMath.cos(FastMath.toRadians((from.y + to.y) / 2));
        return RADIUS_OF_EARTH_IN_M * FastMath.sqrt(dLat * dLat + dLon * dLon) * MAX_ERR_INV;
    }
}
