package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;

import java.util.EnumSet;

/**
 * Label streets with a best-guess at their Level of Traffic Stress, as defined in
 * http://transweb.sjsu.edu/PDFs/research/1005-low-stress-bicycling-network-connectivity.pdf
 *
 * OSM actually doesn't contain enough data to extract a level of traffic stress, so we give our best guess regarding
 * lane widths, etc.
 */
public class LevelOfTrafficStressLabeler {
    /** Set the LTS for this way in the provided flags (not taking into account any intersection LTS at the moment) */
    public void label (Way way, EnumSet<EdgeStore.EdgeFlag> forwardFlags, EnumSet<EdgeStore.EdgeFlag> backFlags) {
        // the general idea behind this function is that we progress from low-stress to higher-stress, bailing out as we go.

        if (!forwardFlags.contains(EdgeStore.EdgeFlag.ALLOWS_CAR) && !backFlags.contains(EdgeStore.EdgeFlag.ALLOWS_CAR)) {
            // no cars permitted on this way, it is LTS 1
            // TODO on street bike lanes/cycletracks digitized as separate infrastructure?
            forwardFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_1);
            backFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_1);
            return;
        }

        // is this a small, low-stress road?
        if (way.hasTag("highway", "residential") || way.hasTag("highway", "living_street")) {
            forwardFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_1);
            backFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_1);
            return;
        }

        // is there a bike lane?
        // we don't have lane widths, so guess from the roadway classification
        boolean hasForwardLane = false;
        boolean hasBackwardLane = false;
        if (way.hasTag("cycleway", "lane")) {
            // there is a bike lane in all directions that cycles are allowed to traverse.
            hasForwardLane = hasBackwardLane = true;
        }

        // TODO handle left-hand-drive countries
        if (way.hasTag("cycleway:left", "lane") || way.hasTag("cycleway", "opposite") || way.hasTag("cycleway:right", "opposite")) {
            hasBackwardLane = true;
        }

        // NB there are fewer conditions here and this is on purpose. Cycleway:opposite means a reverse-flow lane,
        // but cycleway:lane means a lane in all directions traffic is allowed to flow.
        if (way.hasTag("cycleway:left", "opposite") ||  way.hasTag("cycleway:right", "lane")) {
            hasForwardLane = true;
        }

        // TODO arbitrary. Roads up to tertiary with bike lanes are considered LTS 2, roads above tertiary, LTS 3.
        // LTS 3 has defined space, but on fast roads
        if (way.hasTag("highway", "unclassified") || way.hasTag("highway", "tertiary") || way.hasTag("highway", "tertiary_link")) {
            if (hasForwardLane) {
                forwardFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_2);
            }
            else {
                forwardFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_3); // moderate speed single lane street
            }

            if (hasBackwardLane) {
                backFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_2);
            }
            else {
                backFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_3);
            }
        }
        else {
            if (hasForwardLane) {
                forwardFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_3);
            }

            if (hasBackwardLane) {
                backFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_3);
            }
        }

        // if we've assigned nothing, assign LTS 4
        if (!forwardFlags.contains(EdgeStore.EdgeFlag.BIKE_LTS_1) && !forwardFlags.contains(EdgeStore.EdgeFlag.BIKE_LTS_2) &&
                !forwardFlags.contains(EdgeStore.EdgeFlag.BIKE_LTS_3) && !forwardFlags.contains(EdgeStore.EdgeFlag.BIKE_LTS_4))
            forwardFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_4);

        if (!backFlags.contains(EdgeStore.EdgeFlag.BIKE_LTS_1) && !backFlags.contains(EdgeStore.EdgeFlag.BIKE_LTS_2) &&
                !backFlags.contains(EdgeStore.EdgeFlag.BIKE_LTS_3) && !backFlags.contains(EdgeStore.EdgeFlag.BIKE_LTS_4))
            backFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_4);
     }
}
