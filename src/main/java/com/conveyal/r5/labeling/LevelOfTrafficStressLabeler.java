package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.conveyal.r5.streets.EdgeStore.EdgeFlag.BIKE_LTS_EXPLICIT;

/**
 * Label streets with a best-guess at their Level of Traffic Stress, as defined in
 * http://transweb.sjsu.edu/PDFs/research/1005-low-stress-bicycling-network-connectivity.pdf
 *
 * OSM actually doesn't contain enough data to extract a level of traffic stress, so we give our best guess regarding
 * lane widths, etc. This blog post explains how we make this guess:
 * https://blog.conveyal.com/better-measures-of-bike-accessibility-d875ae5ed831
 *
 * Originally the plan was to verify these guesses against actual LTS data, but we never did that because we never
 * got the ground truth data.
 */
public class LevelOfTrafficStressLabeler {
    private static final Logger LOG = LoggerFactory.getLogger(LevelOfTrafficStressLabeler.class);

    /** Match OSM speeds, from http://wiki.openstreetmap.org/wiki/Key:maxspeed */
    private static final Pattern speedPattern = Pattern.compile("^([0-9][\\.0-9]*?) ?(km/h|kmh|kph|mph|knots)?$");

    Set<String> badMaxspeedValues = new HashSet<>();

    Set<String> badLaneValues = new HashSet<>();

    /**
     * Set the LTS for this way in the provided flags (not taking into account any intersection LTS at the moment).
     * This sets flags (passed in as the second and third parameters) from the tags on the OSM Way (first parameter).
     */
    public void label (Way way, EnumSet<EdgeStore.EdgeFlag> forwardFlags, EnumSet<EdgeStore.EdgeFlag> backFlags) {
        // the general idea behind this function is that we progress from low-stress to higher-stress, bailing out as we go.

        // First, if the input OSM data contains LTS tags, use those rather than estimating LTS from road characteristics.
        String ltsTagValue = way.getTag("lts");
        if (ltsTagValue != null) {
            try {
                // Some input Shapefiles have LTS as a floating point number.
                double lts = Double.parseDouble(ltsTagValue);
                if (lts < 1 || lts > 4) {
                    LOG.error("LTS value in OSM tag must be between 1 and 4. It is: " + lts);
                }
                EdgeStore.EdgeFlag ltsFlag = intToLts((int)lts);
                forwardFlags.add(ltsFlag);
                forwardFlags.add(BIKE_LTS_EXPLICIT);
                backFlags.add(ltsFlag);
                backFlags.add(BIKE_LTS_EXPLICIT);
                return;
            } catch (NumberFormatException nfe){
                LOG.error("Could not parse LTS from OSM tag: " + ltsTagValue);
            }
        }

        if (!forwardFlags.contains(EdgeStore.EdgeFlag.ALLOWS_CAR) && !backFlags.contains(EdgeStore.EdgeFlag.ALLOWS_CAR)) {
            // no cars permitted on this way, it is LTS 1
            // TODO on street bike lanes/cycletracks digitized as separate infrastructure?
            forwardFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_1);
            backFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_1);
            return;
        }

        // leave some unlabeled because we don't really know. Also alleys and parking aisles shouldn't "bleed" high LTS
        // into the streets that connect to them
        if (way.hasTag("highway", "service"))
            return;

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

        // extract max speed and lane info
        double maxSpeed = Double.NaN;
        if (way.hasTag("maxspeed")) {
            // parse the max speed tag
            String tagValue = way.getTag("maxspeed");
            maxSpeed = getSpeedKmh(tagValue);
            if (Double.isNaN(maxSpeed)) {
                LOG.debug("Unable to parse maxspeed tag {}", tagValue);
                badMaxspeedValues.add(tagValue);
            }
        }

        int lanes = Integer.MAX_VALUE;
        if (way.hasTag("lanes")) {
            String tagValue = null;
            try {
                tagValue = way.getTag("lanes");
                lanes = Integer.parseInt(tagValue);
            } catch (NumberFormatException e) {
                LOG.debug("Unable to parse lane specification {}", tagValue);
                badLaneValues.add(tagValue);
            }
        }

        EdgeStore.EdgeFlag defaultLts = EdgeStore.EdgeFlag.BIKE_LTS_3;

        // if it's small and slow, LTS 2
        if (lanes <= 3 && maxSpeed <= 25 * 1.61) defaultLts = EdgeStore.EdgeFlag.BIKE_LTS_2;

        // assume that there aren't too many lanes if it's not specified
        if (lanes == Integer.MAX_VALUE && maxSpeed <= 25 * 1.61) defaultLts = EdgeStore.EdgeFlag.BIKE_LTS_2;

        // TODO arbitrary. Roads up to tertiary with bike lanes are considered LTS 2, roads above tertiary, LTS 3.
        // LTS 3 has defined space, but on fast roads
        if (way.hasTag("highway", "unclassified") || way.hasTag("highway", "tertiary") || way.hasTag("highway", "tertiary_link")) {
            // assume that it's not too fast if it's not specified, but only for these smaller roads
            // TODO questionable. Tertiary roads probably tend to be faster than 25 MPH.
            if (lanes <= 3 && Double.isNaN(maxSpeed)) defaultLts = EdgeStore.EdgeFlag.BIKE_LTS_2;

            if (hasForwardLane) {
                forwardFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_2);
            }
            else {
                forwardFlags.add(defaultLts); // moderate speed single lane street
            }

            if (hasBackwardLane) {
                backFlags.add(EdgeStore.EdgeFlag.BIKE_LTS_2);
            }
            else {
                backFlags.add(defaultLts);
            }
        }
        else { // NB includes trunk
            // NB this will be LTS 3 unless this street has a low number of lanes and speed limit, or a low speed limit
            // and unknown number of lanes
            if (hasForwardLane) {
                forwardFlags.add(defaultLts);
            }

            if (hasBackwardLane) {
                backFlags.add(defaultLts);
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

    /**
     * The way the LTS of intersections is incorporated into the model is to give approaches to intersections
     * the highest LTS of any of the streets entering the intersection, unless there is a traffic signal at the intersection.
     */
    public void applyIntersectionCosts(StreetLayer streetLayer) {

        VertexStore.Vertex v = streetLayer.vertexStore.getCursor(0);
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor();

        // we can't re-label until after we've scanned every vertex, because otherwise some edges would
        // get relabeled before we had scanned other vertices, and high-LTS streets would bleed into nearby
        // low-LTS neighborhoods.
        TIntIntMap vertexStresses = new TIntIntHashMap();

        do {
            // signalized intersections are not considered by LTS methodology
            if (v.getFlag(VertexStore.VertexFlag.TRAFFIC_SIGNAL)) continue;

            // otherwise find the max lts
            int maxLts = 1;

            for (TIntIterator it = streetLayer.incomingEdges.get(v.index).iterator(); it.hasNext();) {
                e.seek(it.next());
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_2)) maxLts = Math.max(2, maxLts);
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_3)) maxLts = Math.max(3, maxLts);
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_4)) maxLts = Math.max(4, maxLts);
            }

            for (TIntIterator it = streetLayer.outgoingEdges.get(v.index).iterator(); it.hasNext();) {
                e.seek(it.next());
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_2)) maxLts = Math.max(2, maxLts);
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_3)) maxLts = Math.max(3, maxLts);
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_4)) maxLts = Math.max(4, maxLts);
            }

            vertexStresses.put(v.index, maxLts);

        } while (v.advance());

        for (TIntIntIterator it = vertexStresses.iterator(); it.hasNext();) {
            it.advance();

            v.seek(it.key());

            for (TIntIterator eit = streetLayer.incomingEdges.get(v.index).iterator(); eit.hasNext();) {
                e.seek(eit.next());
                if (e.getFlag(BIKE_LTS_EXPLICIT)) {
                    continue;
                }

                // we do need to check and preserve LTS on this edge, because it can be higher than the intersection
                // LTS if the other end of it is connected to a higher-stress intersection.
                int lts = it.value();
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_2)) lts = Math.max(2, lts);
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_3)) lts = Math.max(3, lts);
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_4)) lts = Math.max(4, lts);

                // clear existing markings
                e.clearFlag(EdgeStore.EdgeFlag.BIKE_LTS_1);
                e.clearFlag(EdgeStore.EdgeFlag.BIKE_LTS_2);
                e.clearFlag(EdgeStore.EdgeFlag.BIKE_LTS_3);
                e.clearFlag(EdgeStore.EdgeFlag.BIKE_LTS_4);

                e.setFlag(intToLts(lts));
            }

            // need to set on both incoming and outgoing b/c it is possible to start or end a search at a high-stress intersection
            for (TIntIterator eit = streetLayer.outgoingEdges.get(v.index).iterator(); eit.hasNext();) {
                e.seek(eit.next());
                if (e.getFlag(BIKE_LTS_EXPLICIT)) {
                    continue;
                }

                // we do need to check and preserve LTS on this edge, because it can be higher than the intersection
                // LTS if the other end of it is connected to a higher-stress intersection.
                int lts = it.value();
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_2)) lts = Math.max(2, lts);
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_3)) lts = Math.max(3, lts);
                if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_4)) lts = Math.max(4, lts);

                // clear existing markings
                e.clearFlag(EdgeStore.EdgeFlag.BIKE_LTS_1);
                e.clearFlag(EdgeStore.EdgeFlag.BIKE_LTS_2);
                e.clearFlag(EdgeStore.EdgeFlag.BIKE_LTS_3);
                e.clearFlag(EdgeStore.EdgeFlag.BIKE_LTS_4);

                e.setFlag(intToLts(lts));
            }
        }
    }

    public static Integer ltsToInt (EdgeStore.EdgeFlag flag) {
        switch (flag) {
            case BIKE_LTS_1: return 1;
            case BIKE_LTS_2: return 2;
            case BIKE_LTS_3: return 3;
            case BIKE_LTS_4: return 4;
            default: return null;
        }
    }

    public static EdgeStore.EdgeFlag intToLts (int lts) {
        if (lts < 2) return EdgeStore.EdgeFlag.BIKE_LTS_1;
        else if (lts == 2) return EdgeStore.EdgeFlag.BIKE_LTS_2;
        else if (lts == 3) return EdgeStore.EdgeFlag.BIKE_LTS_3;
        else return EdgeStore.EdgeFlag.BIKE_LTS_4;
    }

    /** parse an OSM speed tag */
    public static double getSpeedKmh (String maxSpeed) {
        try {
            Matcher m = speedPattern.matcher(maxSpeed);

            // do match op here
            if (!m.matches())
                return Double.NaN;

            double ret = Double.parseDouble(m.group(1));

            // check for non-metric units
            if (m.groupCount() > 1) {
                if ("mph".equals(m.group(2))) ret *= 1.609;
                // seriously? knots?
                else if ("knots".equals(m.group(2))) ret *= 1.852;
                // all other units are assumed to be km/h
            }

            return ret;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /**
     * Call when finished using the instance to print some summary information.
     */
    public void logErrors() {
        if (badMaxspeedValues.size() > 0) LOG.warn("Unrecognized values for maxspeed tag: {}", badMaxspeedValues);
        if (badLaneValues.size() > 0) LOG.warn("Unrecognized values for lane tag: {}", badLaneValues);
    }
}
