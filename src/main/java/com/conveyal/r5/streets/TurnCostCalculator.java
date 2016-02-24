package com.conveyal.r5.streets;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.Mode;
import com.vividsolutions.jts.algorithm.Angle;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.linearref.LocationIndexedLine;

import java.util.Arrays;

/**
 * Compute turn costs.
 */
public class TurnCostCalculator {
    public final StreetLayer layer;

    /** Turn costs for various types of turns, in seconds. These are all specified for drive-on-right countries, and reversed in drive-on-left countries */
    public static final int LEFT_TURN = 10;
    public static final int STRAIGHT_ON = 0;
    public static final int RIGHT_TURN = 10;
    public static final int U_TURN = 6000; // penalize U turns extremely heavily

    public boolean driveOnRight;

    public TurnCostCalculator (StreetLayer layer, boolean driveOnRight) {
        this.layer = layer;
        this.driveOnRight = driveOnRight;
    }

    public int computeTurnCost (int fromEdge, int toEdge, Mode mode) {
        if (mode == Mode.CAR) {
            double angle = computeAngle(fromEdge, toEdge);

            if (angle < 0.25 * Math.PI)
                return STRAIGHT_ON;
            else if (angle < 0.75 * Math.PI)
                return driveOnRight ? RIGHT_TURN : LEFT_TURN;
            else if (angle < 1.25 * Math.PI)
                return U_TURN;
            else if (angle < 1.75 * Math.PI)
                return driveOnRight ? LEFT_TURN : RIGHT_TURN;
            else
                return STRAIGHT_ON;
        }

        return 0;
    }

    /** Compute the angle between two edges, positive, from 0 to 2 * Pi */
    public double computeAngle (int fromEdge, int toEdge) {
        // figure out turn angle
        EdgeStore.Edge e = layer.edgeStore.getCursor(fromEdge);
        VertexStore.Vertex v = layer.vertexStore.getCursor(e.getToVertex());

        Coordinate p1 = pointOnLine(e.getGeometry(), true);
        Coordinate p2 = new Coordinate(v.getLon(), v.getLat());
        e.seek(toEdge);
        Coordinate p3 = pointOnLine(e.getGeometry(), false);

        // figure out the angle. All below computations in radians. Add pi so everything is positive.
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
