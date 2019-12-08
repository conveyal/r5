package com.conveyal.r5.api.util;

import com.conveyal.r5.profile.StreetMode;

import java.util.EnumSet;
import java.util.Set;

/**
 * Modes of transport on access or egress legs
 */
public enum LegMode {
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

    /**
     * Convert between these two enum types.
     * Additional qualifiers (RENT and PARK) on LegMode will be lost in the conversion to StreetMode.
     */
    public static StreetMode toStreetMode (LegMode legMode) {
        if (legMode == LegMode.WALK) {
            return StreetMode.WALK;
        }
        if (legMode == LegMode.BICYCLE || legMode == LegMode.BICYCLE_RENT) {
            return StreetMode.BICYCLE;
        }
        if (legMode == LegMode.CAR || legMode == LegMode.CAR_PARK) {
            return StreetMode.CAR;
        }
        throw new AssertionError("This enum value is not covered by a conditional branch: " + legMode);
    }

    public static EnumSet<StreetMode> toStreetModeSet (EnumSet<LegMode>... legModeSets) {
        EnumSet<StreetMode> streetModes = EnumSet.noneOf(StreetMode.class);
        for (EnumSet<LegMode> legModeSet : legModeSets) {
            for (LegMode legMode : legModeSet) {
                streetModes.add(LegMode.toStreetMode(legMode));
            }
        }
        return streetModes;
    }

}
