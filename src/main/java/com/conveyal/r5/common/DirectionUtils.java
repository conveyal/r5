package com.conveyal.r5.common;

import org.apache.commons.math3.util.FastMath;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

/**
 * Created by mabu on 4.1.2016.
 */
public class DirectionUtils {
    //How far in meters is the
    final static double DIST = 10;

    //180 degrees brads
    public final static byte m180 = radiansToBrads(Math.PI);

    /**
     * Returns the approximate azimuth from coordinate A to B in decimal degrees clockwise from North,
     * in the range (-PI to PI). The computation is exact for small delta between A and B.
     */
    public static double getAzimuth(Coordinate a, Coordinate b) {
        //From Midgard Valhalla library

        final double a_lat = a.y;
        final double a_lon = a.x;

        final double b_lat = b.y;
        final double b_lon = b.x;

        double lat1 = FastMath.toRadians(a_lat);
        double lat2 =  FastMath.toRadians(b_lat);
        double dlng =  FastMath.toRadians(b_lon - a_lon);
        double y =  (FastMath.sin(dlng) * FastMath.cos(lat2));
        double x =  (FastMath.cos(lat1) * FastMath.sin(lat2) - FastMath.sin(lat1) * FastMath.cos(lat2) * FastMath.cos(dlng));
        if (FastMath.abs(x) < 1e-10 && FastMath.abs(y) < 1e-10)
            return Math.PI;
        double bearing = FastMath.atan2(y, x);
        return bearing; // (bearing < 0.0f) ? bearing + 360.0f: bearing;
    }

    /**
     * Computes the angle of the last segment of a LineString or MultiLineString in radians clockwise from North
     * in the range (-180, 180).
     * @param geometry a LineString
     */
    public static double getLastAngle(LineString geometry) {
        //Based on Midgard in Valhalla from Mapzen
        //https://github.com/valhalla/midgard/blob/cdcde8af95e669e1c1331ab6008afb8af14f135e/src/midgard/pointll.cc


        int n = geometry.getNumPoints();
        if (n < 2) {
            return 0.0;
        }
        if (n == 2) {
            return getAzimuth(geometry.getCoordinateN(0), geometry.getCoordinateN(1));
        }

        int i=n-2;
        double d = 0.0;
        double seglength;
        while(d < DIST && i >= 0) {
            seglength = SphericalDistanceLibrary.fastDistance(geometry.getCoordinateN(i), geometry.getCoordinateN(i+1));
            if (d + seglength > DIST) {
                return getAzimuth(geometry.getCoordinateN(i), geometry.getCoordinateN(n-1));
            } else {
                d += seglength;
                i--;
            }
        }

        return getAzimuth(geometry.getCoordinateN(0), geometry.getCoordinateN(n-1));

    }

    /**
     * Computes the angle of the first segment of a LineString or MultiLineString in radians clockwise from North
     * in the range (-PI, PI).
     * @param geometry a LineString
     */
    public static double getFirstAngle(LineString geometry) {
        //Based on Midgard in Valhalla from Mapzen
        int n = geometry.getNumPoints();
        if (n < 2) {
            return 0.0;
        }
        if (n == 2) {
            return getAzimuth(geometry.getCoordinateN(0), geometry.getCoordinateN(1));
        }


        int i = 0;
        double d = 0.0;
        double seglength = 0.0;
        while (d < DIST && i < (n-1)) {
            seglength = SphericalDistanceLibrary.fastDistance(geometry.getCoordinateN(i), geometry.getCoordinateN(i+1));
            if (d+seglength > DIST) {
                return getAzimuth(geometry.getCoordinateN(0), geometry.getCoordinateN(i+1));
            } else {
                d += seglength;
                i++;
            }
        }
        return getAzimuth(geometry.getCoordinateN(0), geometry.getCoordinateN(n-1));
    }

    /** Converts angle in radians to angle in binary radians **/
    public static byte radiansToBrads(double angleRadians) {
        return (byte) Math.round(angleRadians * 128 / Math.PI);
    }

    /**
     * Converts binary radians to angle in degrees
     * FIXME most of our operations can be done more simply and efficiently directly in brads, exploiting overflow.
     *       A circle of 256 brads divides into four sections of 64, with 32 on either side of a cardinal direction.
     */
    public static int bradsToDegree(byte brad) {
        return brad * 180 / 128;
    }

    public static byte getFirstAngleBrads(LineString geometry) {
        return radiansToBrads(getFirstAngle(geometry));
    }

    public static byte getLastAngleBrads(LineString geometry) {
        return radiansToBrads(getLastAngle(geometry));
    }
}
