package com.conveyal.r5.analyst.network.scene;

/// Represents a transit stop in a scene. It will be rendered as a GTFS stop with location_type 0
/// whether or not any trip references it. TransitLayer will also index and link such unused stops,
/// so a stops-only scene should be sufficient for linking tests.
public class SceneStop {

    /// Used as both the GTFS stop_id and stop_name.
    public final String id;

    /// Meters east of the scene origin.
    public final int x;

    /// Meters north of the scene origin.
    public final int y;

    SceneStop (String id, int x, int y) {
        this.id = id;
        this.x = x;
        this.y = y;
    }

    @Override
    public String toString () {
        return String.format("SceneStop %s (%d, %d)", id, x, y);
    }

}
