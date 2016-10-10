package com.conveyal.r5.common;

import com.conveyal.r5.analyst.UnsupportedGeometryException;
import com.conveyal.r5.streets.VertexStore;
import com.vividsolutions.jts.geom.*;
import org.apache.commons.math3.util.FastMath;
import org.geojson.GeoJsonObject;
import org.geojson.LngLatAlt;

import java.util.List;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;

/**
 * Reimplementation of OTP GeometryUtils, using copied code where there are not licensing concerns.
 */
public class GeometryUtils {
    public static final GeometryFactory geometryFactory = new GeometryFactory();

    // average of polar and equatorial, https://en.wikipedia.org/wiki/Earth
    public static final double RADIUS_OF_EARTH_M = 6_367_450;

    /**
     * Convert a org.geojson.Xxxx geometry to a JTS geometry. Copied from same-named class in OTP.
     * Only support Point, Polygon and MultiPolygon for now.
     * @param geoJsonGeom
     * @return The equivalent JTS geometry.
     * @throws UnsupportedGeometryException
     * @author laurentg
     */
    public static Geometry convertGeoJsonToJtsGeometry(GeoJsonObject geoJsonGeom)
            throws UnsupportedGeometryException {
        if (geoJsonGeom instanceof org.geojson.Point) {
            org.geojson.Point geoJsonPoint = (org.geojson.Point) geoJsonGeom;
            return geometryFactory.createPoint(new Coordinate(geoJsonPoint.getCoordinates().getLongitude(), geoJsonPoint
                    .getCoordinates().getLatitude()));

        } else if (geoJsonGeom instanceof org.geojson.Polygon) {
            org.geojson.Polygon geoJsonPolygon = (org.geojson.Polygon) geoJsonGeom;
            LinearRing shell = geometryFactory.createLinearRing(convertPath(geoJsonPolygon.getExteriorRing()));
            LinearRing[] holes = new LinearRing[geoJsonPolygon.getInteriorRings().size()];
            int i = 0;
            for (List<LngLatAlt> hole : geoJsonPolygon.getInteriorRings()) {
                holes[i++] = geometryFactory.createLinearRing(convertPath(hole));
            }
            return geometryFactory.createPolygon(shell, holes);

        } else if (geoJsonGeom instanceof org.geojson.MultiPolygon) {
            org.geojson.MultiPolygon geoJsonMultiPolygon = (org.geojson.MultiPolygon) geoJsonGeom;
            Polygon[] jtsPolygons = new Polygon[geoJsonMultiPolygon.getCoordinates().size()];
            int i = 0;
            for (List<List<LngLatAlt>> geoJsonRings : geoJsonMultiPolygon.getCoordinates()) {
                org.geojson.Polygon geoJsonPoly = new org.geojson.Polygon();
                for (List<LngLatAlt> geoJsonRing : geoJsonRings)
                    geoJsonPoly.add(geoJsonRing);
                jtsPolygons[i++] = (Polygon) convertGeoJsonToJtsGeometry(geoJsonPoly);
            }
            return geometryFactory.createMultiPolygon(jtsPolygons);

        } else if (geoJsonGeom instanceof org.geojson.LineString) {
            org.geojson.LineString geoJsonLineString = (org.geojson.LineString) geoJsonGeom;
            return geometryFactory.createLineString(convertPath(geoJsonLineString.getCoordinates()));

        } else if (geoJsonGeom instanceof org.geojson.MultiLineString) {
            org.geojson.MultiLineString geoJsonMultiLineString = (org.geojson.MultiLineString) geoJsonGeom;
            LineString[] jtsLineStrings = new LineString[geoJsonMultiLineString.getCoordinates().size()];
            int i = 0;
            for (List<LngLatAlt> geoJsonPath : geoJsonMultiLineString.getCoordinates()) {
                org.geojson.LineString geoJsonLineString = new org.geojson.LineString(
                        geoJsonPath.toArray(new LngLatAlt[geoJsonPath.size()]));
                jtsLineStrings[i++] = (LineString) convertGeoJsonToJtsGeometry(geoJsonLineString);
            }
            return geometryFactory.createMultiLineString(jtsLineStrings);
        }

        throw new UnsupportedGeometryException(geoJsonGeom.getClass().toString());
    }

    private static Coordinate[] convertPath(List<LngLatAlt> path) {
        Coordinate[] coords = new Coordinate[path.size()];
        int i = 0;
        for (LngLatAlt p : path) {
            coords[i++] = new Coordinate(p.getLatitude(), p.getLongitude());
        }
        return coords;
    }

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
     */
    public static void expandEnvelopeFixed(Envelope envelope, double radiusMeters) {
        // Intentionally overestimate by scaling for the latitude closest to the equator.
        // convert latitude to floating for use with SphericalDistanceLibrary below
        double floatingLat0 =
                fixedDegreesToFloating(Math.min(Math.abs(envelope.getMaxY()), Math.abs(envelope.getMinY())));
        double yExpansion =
                VertexStore.floatingDegreesToFixed(SphericalDistanceLibrary.metersToDegreesLatitude(radiusMeters));
        double xExpansion =
                VertexStore.floatingDegreesToFixed(SphericalDistanceLibrary.metersToDegreesLongitude(radiusMeters, floatingLat0));
        if (xExpansion < 0 || yExpansion < 0) throw new AssertionError("Buffer distance in geographic units is negative!");
        envelope.expandBy(xExpansion, yExpansion);
    }

}
