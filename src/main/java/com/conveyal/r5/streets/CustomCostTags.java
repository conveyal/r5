package com.conveyal.r5.streets;

import com.conveyal.osmlib.Way;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For both directions (forward and backward) of a single OSM Way, tags used to represent the per-edge factors
 * contributing to generalized costs.
 */
public class CustomCostTags {

    private static final Logger LOG = LoggerFactory.getLogger(CustomCostTags.class);

    final double walkFactor;
    final double bikeFactor;

    public CustomCostTags(Way way) {
        walkFactor = parseDoubleTag(way, "walk_factor");
        bikeFactor = parseDoubleTag(way, "bike_factor");
    }

    /**
     * Read a single tag from the given OSM way and interpret it as a double-precision floating point value.
     * If no tag is present, or if the tag cannot be parsed as a double, use default generalized cost value (1).
     */
    private static double parseDoubleTag (Way way, String tagKey) {
        String tagValue = way.getTag(tagKey);
        if (tagValue == null) {
            return 1;
        }
        try {
            return Double.parseDouble(tagValue);
        } catch (NumberFormatException nfe) {
            LOG.error("Could not parse generalized cost tag as a double: " + tagValue);
            return 1;
        }
    }
}