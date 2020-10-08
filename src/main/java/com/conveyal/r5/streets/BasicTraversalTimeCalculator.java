package com.conveyal.r5.streets;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import org.apache.commons.math3.util.FastMath;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

/**
 * This encapsulates the original/default turning and edge traversal cost calculations that were in the street router.
 * It uses hard-wired values for the amount of time it takes to complete a turn.
 */
public class BasicTraversalTimeCalculator implements TraversalTimeCalculator {

    public final StreetLayer layer;

    // Turn costs for various types of turns, in seconds.
    // These are all specified for drive-on-right countries, and reversed in drive-on-left countries.
    // TODO re-express as "turn against traffic" and "turn with traffic" to make this less confusing.

    public static final int LEFT_TURN = 30;
    public static final int STRAIGHT_ON = 0;
    public static final int RIGHT_TURN = 10;
    public static final int U_TURN = 90; // penalize U turns extremely heavily

    public boolean driveOnRight; // TODO instead of a field, this should be a different implementation class

    public BasicTraversalTimeCalculator (StreetLayer layer, boolean driveOnRight) {
        this.layer = layer;
        this.driveOnRight = driveOnRight;
    }

    @Override
    public int traversalTimeSeconds (EdgeStore.Edge currentEdge, StreetMode streetMode, ProfileRequest req) {
        float speedMetersPerSecond = currentEdge.calculateSpeed(req, streetMode);
        double traversalTimeSeconds = currentEdge.getLengthM() / speedMetersPerSecond;
        return (int) FastMath.ceil(traversalTimeSeconds);
    }

    /**
     * Quantify the difficulty of turning from the fromEdge onto the toEdge with the given streetMode of transport.
     * Quantities should be time-like with units of seconds or perceived seconds.
     * TODO pull this out into an interface to allow generalization to data from generalized cost tags
     */
    @Override
    public int turnTimeSeconds (int fromEdge, int toEdge, StreetMode streetMode) {
        if (streetMode == StreetMode.CAR) {
            double angle = calculateNewTurnAngle(fromEdge, toEdge);
            if (angle < 27)
                return STRAIGHT_ON;
            else if (angle < 153)
                return driveOnRight ? LEFT_TURN : RIGHT_TURN;
            else if (angle < 207)
                return U_TURN;
            else if (angle < 333)
                return driveOnRight ? RIGHT_TURN : LEFT_TURN;
            else
                return STRAIGHT_ON;
        }
        return 0;
    }

    /**
     * Gets in/out angles from edges and calculates angle between them
     * @return angle in degrees from 0-360
     */
    private double calculateNewTurnAngle(int fromEdge, int toEdge) {
        EdgeStore.Edge e = layer.edgeStore.getCursor(fromEdge);
        int outAngle = e.getOutAngle();
        e = layer.edgeStore.getCursor(toEdge);
        int inAngle = e.getInAngle();
        return calculateTurnAngle(outAngle, inAngle);
    }

    /**
     *
     * @param angleIntoIntersectionDeg from edge Out angle in degrees
     * @param angleOutOfIntersectionDeg to edge In angle in degrees
     * @return angle in degrees between 0 and 360
     */
    private int calculateTurnAngle(int angleIntoIntersectionDeg, int angleOutOfIntersectionDeg) {

        if (angleIntoIntersectionDeg < angleOutOfIntersectionDeg) {
            angleIntoIntersectionDeg += 360;
        }

        int turnAngle = angleIntoIntersectionDeg - angleOutOfIntersectionDeg;
        if (!driveOnRight) {
            turnAngle = 360 - turnAngle;
        }

        return turnAngle;

    }

    /** Compute the angle between two edges, positive, from 0 to 2 * Pi. The angle is _counterclockwise_ because that's how it's done in JTS. */
    public double computeAngle (int fromEdge, int toEdge) {
        // figure out turn angle
        EdgeStore.Edge e = layer.edgeStore.getCursor(fromEdge);
        VertexStore.Vertex v = layer.vertexStore.getCursor(e.getToVertex());

        Coordinate p1 = pointOnLine(e.getGeometry(), true);
        Coordinate p2 = new Coordinate(v.getLon(), v.getLat());
        e.seek(toEdge);
        Coordinate p3 = pointOnLine(e.getGeometry(), false);

        // project to local coordinate system, but don't reverse axes
        double cosLat = Math.abs(Math.cos(p2.y * Math.PI / 180));
        p1.x /= cosLat;
        p2.x /= cosLat;
        p3.x /= cosLat;

        // figure out the angle. All below computations in radians. Add 2 * pi so everything is positive.
        double angleIn = Angle.angle(p1, p2);
        double angleOut = Angle.angle(p2, p3);

        double angle = angleOut - angleIn;

        // make sure it's positive
        if (angle < 0) angle += Math.PI * 2;

        return angle;
    }

    public static Coordinate pointOnLine(LineString line, boolean end) {
        Coordinate[] coords = end ? line.reverse().getCoordinates() : line.getCoordinates();

        double distanceSoFar = 0;
        int coordIndex = 1;

        while (distanceSoFar < 10 && coordIndex < coords.length) {
            distanceSoFar += GeometryUtils.distance(coords[coordIndex - 1].x, coords[coordIndex - 1].y, coords[coordIndex].x, coords[coordIndex].y);
            coordIndex++;
        }

        return coords[coordIndex - 1];
    }
}
