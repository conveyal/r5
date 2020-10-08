package com.conveyal.r5.streets;

import com.conveyal.osmlib.Way;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For one direction (forward or backward) on a single OSM Way, the tags used by LADOT to represent the factors
 * contributing to generalized costs. The LADOT data has the unusual characteristic that each OSM way produces exactly
 * one routable edge, i.e. they always begin and end at street intersections with no intermediate intersections along
 * the way.
 *
 * Scripts for producing these tags and documentation on the tags is available at:
 * https://github.com/RSGInc/ladot_analysis_dataprep
 */
public class LaDotCostTags {

    private static final Logger LOG = LoggerFactory.getLogger(LaDotCostTags.class);

    // We should move toward storing full elevation profiles, rather than these derived bins.
    // The 6+ and 10+ bins overlap, the former is used for biking and the latter for walking.
    final double slopePercent2to4;
    final double slopePercent4to6;
    final double slopePercent6plus;
    final double slopePercent10plus;

    // Annual average daily traffic
    final int selfAADT;
    final int crossAADT;
    final int parallelAADT;

    // These are decoded from multiple boolean tags on the OSM data - ideally we'd receive the raw enum data in tags.
    final ControlType controlType;
    final BikeInfrastructure bikeInfrastructure;
    final CrosswalkType crosswalkType;

    final boolean isUnpavedOrAlley;
    final boolean isBusyRoad;

    // Per-edge car speeds. Bike and walk speeds are set in the request (so assumed to be more constant across edges).
    final double speedPeak;
    final double speedOffPeak;

    public LaDotCostTags (Way way, Direction direction) {
        slopePercent2to4 = parseDoubleTag(way, direction.tag("slope_1"));
        slopePercent4to6 = parseDoubleTag(way, direction.tag("slope_2"));
        slopePercent6plus = parseDoubleTag(way, direction.tag("slope_3"));
        slopePercent10plus = parseDoubleTag(way, direction.tag("slope_4"));

        selfAADT = parseIntegerTag(way,"self_aadt");
        crossAADT = parseIntegerTag(way,"cross_aadt");
        parallelAADT = parseIntegerTag(way,"parallel_aadt");

        controlType = ControlType.fromTags(way, direction);
        bikeInfrastructure = BikeInfrastructure.fromTags(way, direction);
        crosswalkType = CrosswalkType.fromTags(way, direction);

        isUnpavedOrAlley = parseBooleanTag(way, "unpaved_alley");
        isBusyRoad = parseBusyRoad(way);

        speedPeak = parseDoubleTag(way, direction.tag("speed_peak"));
        speedOffPeak = parseDoubleTag(way, direction.tag("speed_offpeak"));
    }

    public enum Direction {
        FORWARD, BACKWARD;
        public String tag (String baseTag) {
            return baseTag + ":" + this.name().toLowerCase();
        }
    }

    public enum ControlType {
        NONE, STOP, SIGNAL;
        public static ControlType fromTags (Way way, Direction direction) {
            String value = way.getTag(direction.tag("control_type"));
            if (value == null || value.equalsIgnoreCase("None")) {
                return NONE;
            } else if (value.equalsIgnoreCase("stop")) {
                return STOP;
            } else if (value.equalsIgnoreCase("signal")) {
                return SIGNAL;
            }
            throw new RuntimeException("control_type tag was present but value was not recognized: " + value);
        }
    }

    public enum BikeInfrastructure {
        NONE, BOULEVARD, PATH;
        public static BikeInfrastructure fromTags (Way way, Direction direction) {
            String value = way.getTag(direction.tag("bike_infra"));
            if (value == null || value.equalsIgnoreCase("None") || value.equalsIgnoreCase("no")) {
                return NONE;
            } else if (value.equalsIgnoreCase("path")) {
                return PATH;
            } else if (value.equalsIgnoreCase("blvd")) {
                return BOULEVARD;
            }
            throw new RuntimeException("bike_infra tag was present but value was not recognized: " + value);
        }
    }

    public enum CrosswalkType {
        NONE, UNSIGNALIZED, SIGNALIZED;
        public static CrosswalkType fromTags (Way way, Direction direction) {
            String value = way.getTag(direction.tag("xwalk"));
            if (value == null || value.equalsIgnoreCase("None")) {
                return NONE;
            } else if (value.equalsIgnoreCase("unsig")) {
                return UNSIGNALIZED;
            } else if (value.equalsIgnoreCase("signal")) {
                return SIGNALIZED;
            }
            throw new RuntimeException("Tag was present but value was not recognized.");
        }
    }

    /**
     * Read a single tag from the given OSM way and interpret it as a boolean value.
     * @return true for values "yes" and "true", ignoring case, false for any other value.
     */
    private static boolean parseBooleanTag (Way way, String tagKey) {
        String tagValue = way.getTag(tagKey);
        if (tagValue == null) {
            // LOG.error("All ways are expected to have generalized cost tags. Missing boolean tag '{}'.", tagKey);
            return false;
        }
        return "true".equalsIgnoreCase(tagValue) || "yes".equalsIgnoreCase(tagValue);
    }

    /**
     * Read a single tag from the given OSM way and interpret it as a double-precision floating point value.
     * The tag is expected to be present on the way and be numeric. An exception will be thrown if it is not.
     */
    private static double parseDoubleTag (Way way, String tagKey) {
        String tagValue = way.getTag(tagKey);
        if (tagValue == null) {
            throw new RuntimeException("All ways are expected to have generalized cost tags. Missing: " + tagKey);
        }
        // Our inputs contain nan rather than the NaN expected by Double.parseDouble.
        // NaNs tend to propagate and destroy all calculations they're involved in, so replace them with zeros.
        if ("NaN".equalsIgnoreCase(tagValue)) {
            return 0;
        }
        try {
            return Double.parseDouble(tagValue);
        } catch (NumberFormatException nfe) {
            LOG.error("Could not parse generalized cost tag as a double: " + tagValue);
            return 0;
        }
    }

    /**
     * Read a single tag from the given OSM way and interpret it as an integer value.
     * The tag is expected to be present on the way and be numeric. An exception will be thrown if it is not.
     * Note: A very large number of the AADT fields are zero or NaN.
     */
    private static int parseIntegerTag (Way way, String tagKey) {
        String tagValue = way.getTag(tagKey);
        if (tagValue == null) {
            // LOG.error("All ways are expected to have generalized cost tags. Missing '{}'.", tagKey);
            // Maybe supply a default value as a function parameter?
            return 0;
        }
        try {
            return Integer.parseInt(tagValue);
        } catch (NumberFormatException nfe) {
            // LOG.error("Could not parse generalized cost tag '{}' with value '{}' as an integer.", tagKey, tagValue);
            return 0;
        }
    }

    private static boolean parseBusyRoad (Way way) {
        String highwayValue = way.getTag("highway");
        if (highwayValue == null) return false;
        highwayValue = highwayValue.toLowerCase();
        return  "tertiary".equals(highwayValue) ||
                "tertiary_link".equals(highwayValue) ||
                "secondary".equals(highwayValue) ||
                "secondary_link".equals(highwayValue) ||
                "primary".equals(highwayValue) ||
                "primary_link".equals(highwayValue) ||
                "trunk".equals(highwayValue) ||
                "trunk_link".equals(highwayValue) ||
                "motorway".equals(highwayValue) ||
                "motorway_link".equals(highwayValue);
    }

}
