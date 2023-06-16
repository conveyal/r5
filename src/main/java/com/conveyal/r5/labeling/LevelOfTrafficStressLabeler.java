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

import static com.conveyal.r5.streets.EdgeStore.EdgeFlag.*;
import static com.conveyal.r5.streets.EdgeStore.intToLts;

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
     *
     * The general approach in this function is to look at a bunch of OSM tags (highway, cycleway, maxspeed, lanes) and
     * edge characteristics (allowed modes) progressing from things implying low to high LTS, and returning early when
     * lower LTS has already been established.
     *
     * One reason to work in order of increasing LTS is that LTS is implemented as non-mutually exclusive flags on the
     * edge, and higher LTS flags override lower ones, i.e. if an LTS 4 flag is present the road is seen as LTS 4 even
     * if an LTS 2 flag is present. This should really be changed, but it would be a backward-incompatible change to
     * the network storage format.
     */
    public void label (Way way, EnumSet<EdgeStore.EdgeFlag> forwardFlags, EnumSet<EdgeStore.EdgeFlag> backFlags) {

        // First, if the input OSM data contains LTS tags,
        // use those rather than estimating LTS from road characteristics and return immediately.
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

        // Ways that do not permit cars are deemed LTS 1.
        if (!forwardFlags.contains(EdgeStore.EdgeFlag.ALLOWS_CAR) && !backFlags.contains(EdgeStore.EdgeFlag.ALLOWS_CAR)) {
            // TODO on street bike lanes/cycletracks digitized as separate infrastructure?
            forwardFlags.add(BIKE_LTS_1);
            backFlags.add(BIKE_LTS_1);
            return;
        }

        // Service roads are left unlabeled because we don't really know how stressful they are.
        // Also, alleys and parking aisles shouldn't "bleed" high LTS into the streets that connect to them.
        // (Though perhaps the problem there is that streets shouldn't bleed LTS at all, and certainly not from lower
        // to higher streets in the hierarchy.)
        if (way.hasTag("highway", "service")) {
            return;
        }

        // Small, low-stress road types are definitively set to LTS 1.
        if (way.hasTag("highway", "residential") || way.hasTag("highway", "living_street")) {
            forwardFlags.add(BIKE_LTS_1);
            backFlags.add(BIKE_LTS_1);
            return;
        }

        // If the way has a cycle lane tag, record this fact for later consideration.
        boolean hasForwardLane = false;
        boolean hasBackwardLane = false;
        if (way.hasTag("cycleway", "lane")) {
            // There is a bike lane in all directions that cycles are allowed to traverse.
            hasForwardLane = hasBackwardLane = true;
        }

        // TODO handle left-hand-drive countries.
        if (way.hasTag("cycleway:left", "lane") || way.hasTag("cycleway", "opposite") || way.hasTag("cycleway:right", "opposite")) {
            hasBackwardLane = true;
        }

        // NB there are fewer conditions here and this is on purpose. Cycleway:opposite means a reverse-flow lane,
        // but cycleway:lane means a lane in all directions traffic is allowed to flow.
        if (way.hasTag("cycleway:left", "opposite") ||  way.hasTag("cycleway:right", "lane")) {
            hasForwardLane = true;
        }

        // Extract maximum speed and number of lanes, retaining them for later consideration.
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

        // In the absence of other evidence, we will assign LTS 3. This may be revised downward based on tags.
        EdgeStore.EdgeFlag defaultLts = BIKE_LTS_3;

        // If it's small and slow, lessen stress to LTS 2.
        if (lanes <= 3 && maxSpeed <= 25 * 1.61) defaultLts = BIKE_LTS_2;

        // Assume that streets with a slow maximum speed aren't too stressful if the number of lanes is not specified.
        if (lanes == Integer.MAX_VALUE && maxSpeed <= 25 * 1.61) defaultLts = BIKE_LTS_2;

        // TODO arbitrary. Roads up to tertiary with bike lanes are considered LTS 2, roads above tertiary, LTS 3.
        // LTS 3 has defined space, but on fast roads
        if (way.hasTag("highway", "unclassified") || way.hasTag("highway", "tertiary") || way.hasTag("highway", "tertiary_link")) {
            // assume that it's not too fast if it's not specified, but only for these smaller roads
            // TODO questionable. Tertiary roads probably tend to be faster than 25 MPH.
            if (lanes <= 3 && Double.isNaN(maxSpeed)) defaultLts = BIKE_LTS_2;

            if (hasForwardLane) {
                forwardFlags.add(BIKE_LTS_2);
            } else {
                forwardFlags.add(defaultLts); // moderate speed single lane street
            }
            if (hasBackwardLane) {
                backFlags.add(BIKE_LTS_2);
            } else {
                backFlags.add(defaultLts);
            }
        } else {
            // This clause includes trunk roads, and will default to LTS 3 unless this street has a low number of lanes
            // and speed limit, or a low speed limit and unknown number of lanes.
            if (hasForwardLane) {
                forwardFlags.add(defaultLts);
            }
            if (hasBackwardLane) {
                backFlags.add(defaultLts);
            }
        }

        // If we've assigned nothing, assign LTS 4
        if (!forwardFlags.contains(BIKE_LTS_1) && !forwardFlags.contains(BIKE_LTS_2) &&
                !forwardFlags.contains(BIKE_LTS_3) && !forwardFlags.contains(BIKE_LTS_4)) {
            forwardFlags.add(BIKE_LTS_4);
        }
        if (!backFlags.contains(BIKE_LTS_1) && !backFlags.contains(BIKE_LTS_2) &&
                !backFlags.contains(BIKE_LTS_3) && !backFlags.contains(BIKE_LTS_4)) {
            backFlags.add(BIKE_LTS_4);
        }
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
                if (e.getFlag(BIKE_LTS_2)) maxLts = Math.max(2, maxLts);
                if (e.getFlag(BIKE_LTS_3)) maxLts = Math.max(3, maxLts);
                if (e.getFlag(BIKE_LTS_4)) maxLts = Math.max(4, maxLts);
            }

            for (TIntIterator it = streetLayer.outgoingEdges.get(v.index).iterator(); it.hasNext();) {
                e.seek(it.next());
                if (e.getFlag(BIKE_LTS_2)) maxLts = Math.max(2, maxLts);
                if (e.getFlag(BIKE_LTS_3)) maxLts = Math.max(3, maxLts);
                if (e.getFlag(BIKE_LTS_4)) maxLts = Math.max(4, maxLts);
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
                if (e.getFlag(BIKE_LTS_2)) lts = Math.max(2, lts);
                if (e.getFlag(BIKE_LTS_3)) lts = Math.max(3, lts);
                if (e.getFlag(BIKE_LTS_4)) lts = Math.max(4, lts);
                e.setLts(lts);
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
                if (e.getFlag(BIKE_LTS_2)) lts = Math.max(2, lts);
                if (e.getFlag(BIKE_LTS_3)) lts = Math.max(3, lts);
                if (e.getFlag(BIKE_LTS_4)) lts = Math.max(4, lts);
                e.setLts(lts);
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
