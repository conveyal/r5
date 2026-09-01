package com.conveyal.r5.api.util;

import com.conveyal.r5.profile.StreetMode;

import java.util.EnumSet;
import java.util.Set;

/// Modes of transport on access or egress legs
public enum LegMode {
    WALK, BICYCLE, CAR,
    /// Renting a bicycle
    BICYCLE_RENT,
    /// Park & Ride
    CAR_PARK,
    /// On-demand ride (taxi, van, or minibus ride-hailing service).
    /// This leg mode is unique in that it extends other leg modes present (walk and/or bicycle).
    /// On-demand egress after scheduled transit is not yet supported.
    ON_DEMAND;

    /// Return the heaviest/fastest StreetMode for use in stop finding
    public static StreetMode getDominantStreetMode(Set<LegMode> modes) {
        if (modes.contains(LegMode.CAR)) return StreetMode.CAR;
        else if (modes.contains(LegMode.BICYCLE)) return StreetMode.BICYCLE;
        else return StreetMode.WALK;
    }

    /// Convert between these two enum types.
    /// Additional qualifiers (RENT and PARK) on LegMode will be lost in the conversion to StreetMode.
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
        if (legMode == LegMode.ON_DEMAND) {
            throw new IllegalArgumentException("ON_DEMAND extends other street legs and has no street mode of its own.");
        }
        throw new AssertionError("This enum value is not covered by a conditional branch: " + legMode);
    }

    /// Convert leg modes to the set of street modes they use. ON_DEMAND is skipped, as the street
    /// searches it extends are already present in the set as WALK or BICYCLE.
    public static EnumSet<StreetMode> toStreetModeSet (EnumSet<LegMode>... legModeSets) {
        EnumSet<StreetMode> streetModes = EnumSet.noneOf(StreetMode.class);
        for (EnumSet<LegMode> legModeSet : legModeSets) {
            for (LegMode legMode : legModeSet) {
                if (legMode == LegMode.ON_DEMAND) continue;
                streetModes.add(LegMode.toStreetMode(legMode));
            }
        }
        return streetModes;
    }

}
