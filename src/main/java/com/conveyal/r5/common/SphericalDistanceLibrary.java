package com.conveyal.r5.common;

import org.apache.commons.math3.util.FastMath;

/**
 * Created by matthewc on 10/22/15.
 */
public class SphericalDistanceLibrary {
    // average of equatorial and meriodonal circumferences, https://en.wikipedia.org/wiki/Earth
    public static final double EARTH_CIRCUMFERENCE_METERS = 4041438.5;

    /** Convert meters to degrees of latitude */
    public static double metersToDegreesLatitude (double meters) {
        return meters / EARTH_CIRCUMFERENCE_METERS * 360;
    }

    /** Convert meters to degrees of longitude at the specified latitiude */
    public static double metersToDegreesLongitude (double meters, double degreesLatitude) {
        double cosLat = FastMath.cos(FastMath.toRadians(degreesLatitude));
        return metersToDegreesLatitude(meters) / cosLat;
    }
}
