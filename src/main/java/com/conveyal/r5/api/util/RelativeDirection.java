package com.conveyal.r5.api.util;

/**
 * Represents a turn direction, relative to the current heading.
 *
 * CIRCLE_CLOCKWISE and CIRCLE_CLOCKWISE are used to represent traffic circles.
 *
 */
public enum RelativeDirection {
    DEPART, HARD_LEFT, LEFT, SLIGHTLY_LEFT, CONTINUE, SLIGHTLY_RIGHT, RIGHT, HARD_RIGHT,
    CIRCLE_CLOCKWISE, CIRCLE_COUNTERCLOCKWISE, ELEVATOR, UTURN_LEFT, UTURN_RIGHT;

    public static RelativeDirection setRelativeDirection(double lastAngle, double thisAngle, boolean roundabout) {
        double turn_degree = (((thisAngle - lastAngle) + 360) % 360);

        double ccw_turn_degree = 360 - turn_degree;

        if (roundabout) {
            if (turn_degree > ccw_turn_degree) {
                return CIRCLE_CLOCKWISE;
            } else {
                return CIRCLE_COUNTERCLOCKWISE;
            }
        }

        if (turn_degree < 17 || ccw_turn_degree < 17) {
            return CONTINUE;
        } else if (turn_degree < 40) {
            return SLIGHTLY_RIGHT;
        } else if (ccw_turn_degree < 40) {
            return SLIGHTLY_LEFT;
        } else if (turn_degree < 115) {
            return RIGHT;
        } else if (ccw_turn_degree < 115) {
            return LEFT;
        } else if (turn_degree < 180) {
            return HARD_RIGHT;
        } else {
            return HARD_LEFT;
        }
    }
}
