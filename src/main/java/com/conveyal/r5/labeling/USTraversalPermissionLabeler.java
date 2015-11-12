package com.conveyal.r5.labeling;

import java.util.EnumMap;

/**
 * Traversal permission labeler for the United States.
 * https://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Access-Restrictions#United_States_of_America
 */
public class USTraversalPermissionLabeler extends TraversalPermissionLabeler {
    @Override
    protected EnumMap<Node, Label> getTreeForHighway(String highway) {
        EnumMap<Node, Label> tree = getDefaultTree();
        if ("motorway".equals(highway) || "motorway_link".equals(highway)) {
            tree.put(Node.ACCESS, Label.NO);
            tree.put(Node.CAR, Label.YES);
        }
        else if ("pedestrian".equals(highway) || "path".equals(highway) || "cycleway".equals(highway) || "bridleway".equals(highway)) {
            tree.put(Node.BICYCLE, Label.YES);
            tree.put(Node.FOOT, Label.YES);
            tree.put(Node.CAR, Label.NO);
        }
        else if ("footway".equals(highway)){
            tree.put(Node.FOOT, Label.YES);
            tree.put(Node.VEHICLE, Label.NO);
        }
        else {
            // assume all access
            tree.put(Node.ACCESS, Label.YES);
        }

        return tree;
    }
}
