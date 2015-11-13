package com.conveyal.r5.point_to_point.builder;

import java.util.HashMap;
import java.util.Map;

/**
 * This class have max speed based on highway tags
 *
 */
public class SpeedConfig {
    /**
     * Can be "km/h", "kmh", "kmph", "kph" or mph.
     */
    public SpeedUnit units;

    /**
     * Map of tags and speeds
     */
    public Map<String, Integer> values;

    /**
     * Speed for all the streets for which speed isn't specified
     */
    public int defaultSpeed;

    /**
     * Build a speed factory given a config node, or fallback to the default if none is specified.
     *
     * Units can be (km/h|kmh|kmph|kph or mph or knots) all values needs to be in provided units.
     * Values are OSM highway tag value and speed. All tags need to be provided.
     * defaultSpeed is speed if none of tags matches and street doesn't have maxspeed.
     * Accepts this format:
     * <pre>
     * speeds:{
     *   units:"km/h",
     *   values:{
     *       "motorway": 130,
     *       "motorway_link": 100,
     *       "trunk": 110,
     *       "trunk_link": 100,
     *       "primary": 90,
     *       "primary_link": 90,
     *       "secondary": 50,
     *       "secondary_link": 40,
     *       "tertiary": 40,
     *       "tertiary_link": 40,
     *       "living_street": 10,
     *       "pedestrian": 5,
     *       "residential": 50,
     *       "unclassified": 40,
     *       "service": 25,
     *       "track": 16,
     *       "road": 40
     *   },
     *   "defaultSpeed":40
     * }
     * </pre>
     */
    public SpeedConfig() {
    }

    public static SpeedConfig defaultConfig() {
        SpeedConfig speedConfig = new SpeedConfig();
        speedConfig.units = SpeedUnit.MPH;
        speedConfig.addCarSpeed("motorway", 65);
        speedConfig.addCarSpeed("motorway_link", 35);
        speedConfig.addCarSpeed("trunk", 55);
        speedConfig.addCarSpeed("trunk_link", 35);
        speedConfig.addCarSpeed("primary", 45);
        speedConfig.addCarSpeed("primary_link", 25);
        speedConfig.addCarSpeed("secondary", 35);
        speedConfig.addCarSpeed("secondary_link", 25);
        speedConfig.addCarSpeed("tertiary", 25);
        speedConfig.addCarSpeed("tertiary_link", 25);
        speedConfig.addCarSpeed("living_street", 5);
        speedConfig.defaultSpeed = 25;
        return speedConfig;
    }

    private void addCarSpeed(String tag, int carSpeedInUnit) {
        if (values == null) {
            values = new HashMap<>(12);
        }
        values.put(tag, carSpeedInUnit);
    }
}
