package com.conveyal.r5.api.util;

/**
 * TODO: this could also be copressed like Mapquest is doing
 */
public class Elevation {
    //Distance from start of segment in meters @notnull
    public float distance;
    //Height in m at this distance @notnull
    public float elevation;

    public Elevation(float distance, float height) {
        this.distance = distance;
        this.elevation = height;
    }
}
