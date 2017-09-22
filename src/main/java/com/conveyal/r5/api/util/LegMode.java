package com.conveyal.r5.api.util;

import com.conveyal.r5.profile.StreetMode;

import java.util.Set;

/**
 * Modes of transport on ingress egress legs
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
}
