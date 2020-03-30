package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;

import java.util.EnumSet;

/**
 * This sets the flags on edges indicating what category of edge they are, e.g. stairs, bike path, sidewalk.
 */
public class TypeOfEdgeLabeler {


    private boolean isCycleway (Way way, boolean back) {
        boolean bidirectionalCycleway = way.hasTag("highway", "cycleway") ||
            (way.hasTag("highway", "path") && way.hasTag("bicycle", "designated") && way.hasTag("foot", "designated")) ||
            way.hasTag("cycleway", "lane") ||
            way.hasTag("cycleway", "track");
        if (bidirectionalCycleway) {
            if (way.hasTag("oneway")) {
                if (TraversalPermissionLabeler.Label.fromTag(way.getTag("oneway")) == TraversalPermissionLabeler.Label.YES) {
                    if (!back) {
                        return true;
                    } else {
                        return false;
                    }
                }
            } else {
                return true;
            }
        }

        boolean has_cycleway_opposite = way.hasTag("cycleway", "opposite_lane") || way.hasTag("cycleway", "opposite_track");

        if (back) {
            String cycleway_left = way.getTag("cycleway:left");
            if (cycleway_left != null && TraversalPermissionLabeler.Label.fromTag(cycleway_left) == TraversalPermissionLabeler.Label.YES) {
                return true;
            }
            //if Oneway=true and has cycleway=opposite_lane/track return true on backward edge
            if (has_cycleway_opposite && way.hasTag("oneway")) {
                if (TraversalPermissionLabeler.Label.fromTag(way.getTag("oneway")) == TraversalPermissionLabeler.Label.YES) {
                    return true;
                }
            }
        } else {
            String cycleway_right = way.getTag("cycleway:right");
            if (cycleway_right != null && TraversalPermissionLabeler.Label.fromTag(cycleway_right) == TraversalPermissionLabeler.Label.YES) {
                return true;
            }
            //if Oneway=reverse and has cycleway=opposite_lane/track return true on forward edge
            if (has_cycleway_opposite && way.hasTag("oneway")) {
                if (way.getTag("oneway").equals("-1") || way.getTag("oneway").equals("reverse")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSidewalk(Way way, boolean back) {
        //Road has a sidewalk
        if (way.hasTag("sidewalk")) {
            String sidewalk = way.getTag("sidewalk").toLowerCase();

            //sidewalks on both side
            if (sidewalk.equals("both")) {
                return true;
            } else if (sidewalk.equals("none") || sidewalk.equals("no") || sidewalk.equals("false")) {
                return false;
            }
            if (!back) {
                //sidewalk on the right for forward edge
                if (sidewalk.equals("right")) {
                    return true;
                }
            } else {
                //sidewalk on the left for backward edge
                if (sidewalk.equals("left")) {
                    return true;
                }
            }
            //sidewalk as separate way
        } else if (way.hasTag("highway", "footway") && way.hasTag("footway", "sidewalk")) {
            return true;
            //is implied to be sidewalk
        } else if ((way.hasTag("highway", "cycleway") && way.hasTag("foot", "designated")) ||
            (way.hasTag("highway", "path") && way.hasTag("bicycle", "designated") && way.hasTag("foot", "designated"))){
            return true;
            //implicit sidewalks with cycleways next to street
        } else if (way.hasTag("cycleway", "track") && way.hasTag("segregated", "yes")) {
            return true;
        }
        return false;
    }

    /**
     * Adds Stairs, bike path, sidewalk and crossing flags to ways.
     * This sets flags (passed in as the second and third parameters) from the tags on the OSM Way (first parameter).
     */
    public void label (Way way, EnumSet<EdgeStore.EdgeFlag> forwardFlags, EnumSet<EdgeStore.EdgeFlag> backFlags) {
        if (way.hasTag("highway", "steps")) {
            forwardFlags.add(EdgeStore.EdgeFlag.STAIRS);
            backFlags.add(EdgeStore.EdgeFlag.STAIRS);
        }
        // Tunnels, covered roads and motorways are unlikely places for origins, destinations, or park and rides.
        if (!(way.hasTag("tunnel", "yes") || way.hasTag("covered", "yes") || way.hasTag("highway", "motorway"))) {
            forwardFlags.add(EdgeStore.EdgeFlag.LINKABLE);
            backFlags.add(EdgeStore.EdgeFlag.LINKABLE);
        }
        if (forwardFlags.contains(EdgeStore.EdgeFlag.ALLOWS_BIKE) && isCycleway(way , false)) {
            forwardFlags.add(EdgeStore.EdgeFlag.BIKE_PATH);
        }
        if (backFlags.contains(EdgeStore.EdgeFlag.ALLOWS_BIKE) && isCycleway(way, true)) {
            backFlags.add(EdgeStore.EdgeFlag.BIKE_PATH);
        }

        if (isSidewalk(way, false)) {
            forwardFlags.add(EdgeStore.EdgeFlag.SIDEWALK);
        }
        if (isSidewalk(way, true)) {
            backFlags.add(EdgeStore.EdgeFlag.SIDEWALK);
        }

        if (way.hasTag("footway", "crossing") || way.hasTag("cycleway", "crossing")) {
            forwardFlags.add(EdgeStore.EdgeFlag.CROSSING);
            backFlags.add(EdgeStore.EdgeFlag.CROSSING);
        }

        if (way.hasTag("junction", "roundabout")) {
            forwardFlags.add(EdgeStore.EdgeFlag.ROUNDABOUT);
            backFlags.add(EdgeStore.EdgeFlag.ROUNDABOUT);
        }

        if (way.hasTag("highway", "platform")
            || way.hasTag("public_transport", "platform")
            || way.hasTag("railway", "platform")) {
            forwardFlags.add(EdgeStore.EdgeFlag.PLATFORM);
            backFlags.add(EdgeStore.EdgeFlag.PLATFORM);
        }


    }
}
