package com.conveyal.r5.api.util;

import com.conveyal.r5.profile.StreetMode;

import java.util.Set;

/**
 * Modes of transport on access or egress legs
 */
public enum  LegMode {
    WALK, BICYCLE, CAR,
    //Renting a bicycle
    BICYCLE_RENT,
    //Park & Ride
    CAR_PARK;

    /** Return the heaviest/fastest StreetMode for use in stop finding */
    public static StreetMode getDominantStreetMode(Set<LegMode> modes) {
        if (modes.contains(LegMode.CAR)) return StreetMode.CAR;
        else if (modes.contains(LegMode.BICYCLE)) return StreetMode.BICYCLE;
        else return StreetMode.WALK;
    }

    /** Convert between these two enum types */
    public static StreetMode toStreetMode (LegMode legMode) {
        if (legMode == LegMode.WALK) {
            return StreetMode.WALK;
        }
        if (legMode == LegMode.BICYCLE) {
            return StreetMode.BICYCLE;
        }
        if (legMode == LegMode.CAR) {
            return StreetMode.CAR;
        }
        throw new RuntimeException("Unrecognized mode.");
    }

}
