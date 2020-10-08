package com.conveyal.gtfs.util;

import com.conveyal.gtfs.model.Stop;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.util.FastMath;
import org.locationtech.jts.geom.Coordinate;

/**
 * The methods and classes in this package should eventually be part of a shared Conveyal library.
 */
public abstract class Util {

    public static final double METERS_PER_DEGREE_LATITUDE = 111111.111;

    public static String human (int n) {
        if (n >= 1000000000) return String.format("%.1fG", n/1000000000.0);
        if (n >= 1000000) return String.format("%.1fM", n/1000000.0);
        if (n >= 1000) return String.format("%dk", n/1000);
        else return String.format("%d", n);
    }

    public static double yMetersForLat (double latDegrees) {
        return latDegrees * METERS_PER_DEGREE_LATITUDE;
    }

    public static double xMetersForLon (double latDegrees, double lonDegrees) {
        double xScale = FastMath.cos(FastMath.toRadians(latDegrees));
        return xScale * lonDegrees * METERS_PER_DEGREE_LATITUDE;
    }

    public static Coordinate projectLatLonToMeters (double lat, double lon) {
        return new Coordinate(xMetersForLon(lon, lat), yMetersForLat(lat));
    }

    public static String getCoordString(Stop stop) {
        return String.format("lat=%f; lon=%f", stop.stop_lat, stop.stop_lon);
    }

    /**
     * @return Equirectangular approximation to distance.
     */
    public static double fastDistance (double lat0, double lon0, double lat1, double lon1) {
        double midLat = (lat0 + lat1) / 2;
        double xscale = FastMath.cos(FastMath.toRadians(midLat));
        double dx = xscale * (lon1 - lon0);
        double dy = (lat1 - lat0);
        return FastMath.sqrt(dx * dx + dy * dy) * METERS_PER_DEGREE_LATITUDE;
    }

    /**
     * Generate a random unique prefix of n lowercase letters.
     * We can't count on sql table or schema names being case sensitive or tolerating (leading) digits.
     * For n=10, number of possibilities is 26^10 or 1.4E14.
     *
     * The approximate probability of a hash collision is k^2/2H where H is the number of possible hash values and
     * k is the number of items hashed.
     *
     * SHA1 is 160 bits, MD5 is 128 bits, and UUIDs are 128 bits with only 122 actually random.
     * To reach the uniqueness of a UUID you need math.log(2**122, 26) or about 26 letters.
     * An MD5 can be represented as 32 hex digits so we don't save much length, but we do make it entirely alphabetical.
     * log base 2 of 26 is about 4.7, so each character represents about 4.7 bits of randomness.
     *
     * The question remains of whether we need globally unique IDs or just application-unique IDs. The downside of
     * generating IDs sequentially or with little randomness is that when multiple databases are created we'll have
     * feeds with the same IDs as older or other databases, allowing possible accidental collisions with strange
     * failure modes.
     *
     *
     */
    public static String randomIdString() {
        MersenneTwister twister = new MersenneTwister();
        final int length = 27;
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) ('a' + twister.nextInt(26));
        }
        // Add a visual separator, which makes these easier to distinguish at a glance
        chars[4] = '_';
        return new String(chars);
    }

}
