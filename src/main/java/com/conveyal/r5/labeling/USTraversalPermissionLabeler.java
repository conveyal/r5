package com.conveyal.r5.labeling;


/**
 * Traversal permission labeler for the United States, adding things that differ from the base TraversalPermissionLabeler.
 * https://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Access-Restrictions#United_States_of_America
 */
public class USTraversalPermissionLabeler extends TraversalPermissionLabeler {
    static {
        addPermissions("pedestrian", "bicycle=yes");
        addPermissions("bridleway", "bicycle=yes;foot=yes"); //horse=yes but we don't support horse
        addPermissions("cycleway", "bicycle=yes;foot=yes");
    }

}
