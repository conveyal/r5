package com.conveyal.r5.labeling;


/**
 * Traversal permission labeler that restricts walking on most driving ways (useful for networks with complete
 * sidewalks). Also includes permissions for the United States (see USTraversalPermissionLabeler).
 */
public class SidewalkTraversalPermissionLabeler extends TraversalPermissionLabeler {
    static {
        addPermissions("pedestrian", "bicycle=yes");
        addPermissions("bridleway", "bicycle=yes;foot=yes"); //horse=yes but we don't support horse
        addPermissions("cycleway", "bicycle=yes;foot=yes");
        addPermissions("trunk|primary|secondary|tertiary|unclassified|residential|living_street|road|service|track",
                "access=yes;foot=no"); // Note foot=no

    }

}
