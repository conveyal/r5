package com.conveyal.r5.common;

import com.conveyal.r5.analyst.UnsupportedGeometryException;
import com.vividsolutions.jts.geom.*;
import org.apache.commons.math3.util.FastMath;
import org.geojson.GeoJsonObject;
import org.geojson.LngLatAlt;

import java.util.List;

/**
 * Reimplementation of OTP GeometryUtils, using copied code where there are not licensing concerns.
 */
public class GeometryUtils {
    public static final GeometryFactory geometryFactory = new GeometryFactory();

    // average of polar and equatorial, https://en.wikipedia.org/wiki/Earth
    private static final double RADIUS_OF_EARTH_M = 6367.45;

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

        double thetaAvg = (theta0 + theta1) / 2;
        double lambdaAvg = (lambda0 + lambda1) / 2;
        double sin2theta = FastMath.pow(FastMath.sin(thetaAvg), 2);
        double sin2lambda = FastMath.pow(FastMath.sin(lambdaAvg), 2);

        double underRadical = sin2theta + FastMath.cos(theta0) * FastMath.cos(theta1) * sin2lambda;
        return 2 * RADIUS_OF_EARTH_M * FastMath.asin(FastMath.sqrt(underRadical));
    }

    public static double segmentFraction(int fLon0, int fLat0, int fLon1, int fLat1, int fixLon, int fixLat, double cosLat) {
        // convert to local cartesian system
        fLon0 /= cosLat;
        fLon1 /= cosLat;
        fixLon /= cosLat;

        // TODO can we do this with fixed-point math?
        LineSegment seg = new LineSegment(fLon0, fLat0, fLon1, fLat1);
        return seg.segmentFraction(new Coordinate(fixLon, fixLat));
    }
}
