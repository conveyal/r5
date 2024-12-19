package com.conveyal.r5.common;

import com.conveyal.analysis.datasource.DataSourceException;
import com.conveyal.r5.streets.VertexStore;
import org.apache.commons.math3.util.FastMath;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;
import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * Reimplementation of OTP GeometryUtils, using copied code where there are not licensing concerns.
 * Also contains reusable methods for validating WGS84 envelopes and latitude and longitude values.
 */
public class GeometryUtils {
    public static final GeometryFactory geometryFactory = new GeometryFactory();

    /** Average of polar and equatorial radii, https://en.wikipedia.org/wiki/Earth */
    public static final double RADIUS_OF_EARTH_M = 6_367_450;

    /** Maximum area allowed for the bounding box of uploaded files -- large enough for Europe.  */
    private static final double MAX_BOUNDING_BOX_AREA_SQ_KM = 1_000_000;

    /**
     * Haversine formula for distance on the sphere. We used to have a fastDistance function that would estimate this
     * quickly, but I'm not convinced we actually need it.
     * @return distance in meters
     */
    public static double distance (double lat0, double lon0, double lat1, double lon1) {
        double theta0 = FastMath.toRadians(lat0);
        double theta1 = FastMath.toRadians(lat1);
        double lambda0 = FastMath.toRadians(lon0);
        double lambda1 = FastMath.toRadians(lon1);

        double thetaComb = (theta1 - theta0) / 2;
        double lambdaComb = (lambda1 - lambda0) / 2;
        double sin2theta = FastMath.pow(FastMath.sin(thetaComb), 2);
        double sin2lambda = FastMath.pow(FastMath.sin(lambdaComb), 2);

        double underRadical = sin2theta + FastMath.cos(theta0) * FastMath.cos(theta1) * sin2lambda;
        return 2 * RADIUS_OF_EARTH_M * FastMath.asin(FastMath.sqrt(underRadical));
    }

    /**
     * Project the point (fixedLon, fixedLat) onto the line segment from (fixedLon0, fixedLat0) to
     * (fixedLon1, fixedLat1) and return the fractional distance of the projected location along the segment as a
     * double in the range [0, 1]. All coordinates are fixed-precision geographic.
     * Pass in the cosine of the latitude to avoid repeatedly performing the expensive cosine operation.
     */
    public static double segmentFraction(int fixedLon0, int fixedLat0, int fixedLon1, int fixedLat1,
                                         int fixedLon, int fixedLat, double cosLat) {
        // Adjust x scale to account for lines of longitude converging toward the poles.
        fixedLon0 = (int) (fixedLon0 / cosLat);
        fixedLon1 = (int) (fixedLon1 / cosLat);
        fixedLon  = (int) (fixedLon / cosLat);
        LineSegment seg = new LineSegment(fixedLon0, fixedLat0, fixedLon1, fixedLat1);
        return seg.segmentFraction(new Coordinate(fixedLon, fixedLat));
    }

    /**
     * Given an envelope in fixed-point degrees, enlarge it by at least the given number of meters in all directions.
     * Intentionally slightly oversizes the rectangle by scaling longitudes based on the latitude closest to the equator.
     * Querying for objects inside the envelope will overselect, which is a common characteristic of spatial indexes.
     * You'll need to filter the resulting set of selected objects using more accurate distances.
     */
    public static void expandEnvelopeFixed(Envelope envelope, double radiusMeters) {
        // Convert latitude to floating for use with SphericalDistanceLibrary below
        double floatingLat0 =
                fixedDegreesToFloating(Math.min(Math.abs(envelope.getMaxY()), Math.abs(envelope.getMinY())));
        double yExpansion =
                VertexStore.floatingDegreesToFixed(SphericalDistanceLibrary.metersToDegreesLatitude(radiusMeters));
        double xExpansion =
                VertexStore.floatingDegreesToFixed(SphericalDistanceLibrary.metersToDegreesLongitude(radiusMeters, floatingLat0));
        if (xExpansion < 0 || yExpansion < 0) {
            throw new AssertionError("Buffer distance in geographic units is negative!");
        }
        envelope.expandBy(xExpansion, yExpansion);
    }

    public static boolean containsPoint(Geometry geometry, double x, double y) {
        return geometry.contains(geometryFactory.createPoint(new Coordinate(x, y)));
    }

    public static Envelope floatingWgsEnvelopeToFixed (Envelope floatingWgsEnvelope) {
        double fixedMinX = floatingDegreesToFixed(floatingWgsEnvelope.getMinX());
        double fixedMaxX = floatingDegreesToFixed(floatingWgsEnvelope.getMaxX());
        double fixedMinY = floatingDegreesToFixed(floatingWgsEnvelope.getMinY());
        double fixedMaxY = floatingDegreesToFixed(floatingWgsEnvelope.getMaxY());
        return new Envelope(fixedMinX, fixedMaxX, fixedMinY, fixedMaxY);
    }

    //// Methods for range-checking points and envelopes in WGS84

    /**
     * We have to range-check the envelope before checking its size. Large unprojected y values interpreted as latitudes
     * can yield negative cosines, producing negative estimated areas, producing false negatives on size checks.
     */
    private static void checkWgsEnvelopeRange (Envelope envelope) {
        checkLon(envelope.getMinX());
        checkLon(envelope.getMaxX());
        checkLat(envelope.getMinY());
        checkLat(envelope.getMaxY());
    }

    public static void checkLon (double longitude) {
        if (!Double.isFinite(longitude) || Math.abs(longitude) > 180) {
            throw new DataSourceException("Longitude is not a finite number with absolute value below 180.");
        }
    }

    public static void checkLat (double latitude) {
        // Longyearbyen on the Svalbard archipelago is the world's northernmost permanent settlement (78 degrees N).
        if (!Double.isFinite(latitude) || Math.abs(latitude) > 80) {
            throw new DataSourceException("Latitude is not a finite number with absolute value below 80.");
        }
    }

    /**
     * Throw an exception if the envelope appears to be constructed from points spanning the 180 degree meridian.
     * We check whether the envelope becomes narrower when its left edge is expressed as a longitude greater than 180
     * (shifted east by 180 degrees) and has points anywhere near the 180 degree line.
     * The envelope must already be validated with checkWgsEnvelopeRange to ensure meaningful results.
     */
    private static void checkWgsEnvelopeAntimeridian (Envelope envelope, String thingBeingChecked) {
        double widthAcrossAntimeridian = (envelope.getMinX() + 180) - envelope.getMaxX();
        boolean nearAntimeridian =
                Math.abs(envelope.getMinX() - 180D) < 10 || Math.abs(envelope.getMaxX() - 180D) < 10;
        checkArgument(
                !nearAntimeridian || envelope.getWidth() < widthAcrossAntimeridian,
                thingBeingChecked + " may not span the antimeridian (180 degrees longitude)."
        );
    }

    /**
     * @return the approximate area of an Envelope in WGS84 lat/lon coordinates, in square kilometers.
     */
    public static double roughWgsEnvelopeArea (Envelope wgsEnvelope) {
        double lon0 = wgsEnvelope.getMinX();
        double lon1 = wgsEnvelope.getMaxX();
        double lat0 = wgsEnvelope.getMinY();
        double lat1 = wgsEnvelope.getMaxY();
        double height = lat1 - lat0;
        double width = lon1 - lon0;
        final double KM_PER_DEGREE_LAT = 111.133;
        // Scale the x direction as if the Earth was a sphere.
        // Error above the middle latitude should approximately cancel out error below that latitude.
        double averageLat = (lat0 + lat1) / 2;
        double xScale = FastMath.cos(FastMath.toRadians(averageLat));
        double area = (height * KM_PER_DEGREE_LAT) * (width * KM_PER_DEGREE_LAT * xScale);
        return area;
    }

    /**
     * Throw an exception if the provided envelope is too big for a reasonable destination grid.
     * Should also catch cases where data sets include points on both sides of the 180 degree meridian.
     * This static utility method can be reused to test other automatically determined bounds such as those
     * from OSM or GTFS uploads.
     */
    public static void checkWgsEnvelopeSize (Envelope envelope, String thingBeingChecked) {
        checkWgsEnvelopeRange(envelope);
        checkWgsEnvelopeAntimeridian(envelope, thingBeingChecked);
        if (roughWgsEnvelopeArea(envelope) > MAX_BOUNDING_BOX_AREA_SQ_KM) {
            throw new IllegalArgumentException(String.format(
                    "Geographic extent of %s (%.0f km2) exceeds limit of %.0f km2.",
                    thingBeingChecked, roughWgsEnvelopeArea(envelope), MAX_BOUNDING_BOX_AREA_SQ_KM
            ));
        }
    }

}
