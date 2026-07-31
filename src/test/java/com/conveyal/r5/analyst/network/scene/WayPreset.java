package com.conveyal.r5.analyst.network.scene;

import java.util.Map;

/// Common road and path types for scene ways. Each preset is associated with standard OSM tags.
/// Edge types and permissions should be derived from these tags as in production network builds.
/// Additional tags can be added to any single way with [SceneWay#tag]. Preset tags cannot be
/// overwritten, so a way that needs a different value for the highway key needs a different preset.
public enum WayPreset {

    /// An ordinary two-way local street allowing cars, bikes and pedestrians, of a kind likely
    /// to be near a bus station.
    STREET(Map.of("highway", "tertiary")),

    /// A minor access road such as a frontage road or station forecourt, both drivable
    /// and walkable, so a test scene can use one as a road that is physically near a stop but is
    /// not the stop's intended access route.
    SERVICE(Map.of("highway", "service")),

    /// A grade-separated carriageway that allows only cars, excluded from stop linking.
    MOTORWAY(Map.of("highway", "motorway")),

    /// A motorway ramp. Ramps receive foot=no and bicycle=no through the permission labeler's
    /// handling of `_link` road types, but currently remain linkable for the CAR mode.
    /// FIXME that linking is probably an oversight and should eventually be tested and changed.
    RAMP(Map.of("highway", "motorway_link")),

    /// A pedestrian-only path that should not be used by cars or linked to for the CAR mode.
    FOOTPATH(Map.of("highway", "footway")),

    /// A pedestrian street or plaza.
    PEDESTRIAN(Map.of("highway", "pedestrian"));

    /// OSM tags applied to every way created with this preset.
    public final Map<String, String> tags;

    WayPreset (Map<String, String> tags) {
        this.tags = tags;
    }

}
