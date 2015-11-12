package com.conveyal.r5.profile;

/**
 * Represents a travel mode.
 */
public enum Mode {
    WALK,
    BICYCLE,
    CAR,
    TRANSIT;

    public boolean isTransit() {
        return this == TRANSIT;
    }
}
