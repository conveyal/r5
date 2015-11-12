package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;

import java.util.EnumMap;
import java.util.EnumSet;

/**
 * Label edges with their traversal permissions, see https://wiki.openstreetmap.org/wiki/Computing_access_restrictions#Algorithm
 * and also prior work by Marko Burjek: https://github.com/buma/OpenTripPlanner-Maribor/blob/8eafa3ad9f1426877c6da3d730eaea46c6de35cf/src/main/java/org/opentripplanner/streets/permissions/AccessRestrictionsAlgorithm.java
 * Note that there are country-specific classes that contain defaults for particular countries, see https://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Access-Restrictions
 */
public abstract class TraversalPermissionLabeler {
    public EnumSet<EdgeStore.EdgeFlag> getPermissions(Way way, boolean back) {
        if (!way.hasTag("highway")) {
            // no permissions
            return EnumSet.noneOf(EdgeStore.EdgeFlag.class);
        }

        EnumMap<Node, Label> tree = getTreeForHighway(way.getTag("highway").toLowerCase().trim());
        applySpecificPermissions(tree, way);

        EnumSet<EdgeStore.EdgeFlag> ret = EnumSet.noneOf(EdgeStore.EdgeFlag.class);

        if (walk(tree, Node.FOOT)) ret.add(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        if (walk(tree, Node.BICYCLE)) ret.add(EdgeStore.EdgeFlag.ALLOWS_BIKE);
        if (walk(tree, Node.CAR)) ret.add(EdgeStore.EdgeFlag.ALLOWS_CAR);
        return ret;
    }

    /** returns whether this node is permitted traversal anywhere in the hierarchy */
    private boolean walk (EnumMap<Node, Label> tree, Node node) {
        do {
            if (tree.get(node) == Label.YES)
                return true;
        } while ((node = node.getParent()) != null);

        return false;
    }

    /** apply any specific permissions that may exist */
    private void applySpecificPermissions (EnumMap<Node, Label> tree, Way way) {
        // start from the root of the tree
        if (way.hasTag("access")) applyLabel(Node.ACCESS, Label.fromTag(way.getTag("access")), tree);
        if (way.hasTag("foot")) applyLabel(Node.FOOT, Label.fromTag(way.getTag("foot")), tree);
        if (way.hasTag("vehicle")) applyLabel(Node.VEHICLE, Label.fromTag(way.getTag("vehicle")), tree);
        if (way.hasTag("bicycle")) applyLabel(Node.BICYCLE, Label.fromTag(way.getTag("bicycle")), tree);
        if (way.hasTag("motor_vehicle")) applyLabel(Node.CAR, Label.fromTag(way.getTag("motor_vehicle")), tree);
        // motorcar takes precedence over motor_vehicle
        if (way.hasTag("motorcar")) applyLabel(Node.CAR, Label.fromTag(way.getTag("motorcar")), tree);
    }

    /** apply label hierarchically. This is not explicitly in the spec but we want access=no to override default cycling permissions, for example. */
    protected void applyLabel(Node node, Label label, EnumMap<Node, Label> tree) {
        if (label == Label.UNKNOWN) return;

        tree.put(node, label);

        Node[] children = node.getChildren();

        if (children != null) {
            for (Node child : children) {
                applyLabel(child, label, tree);
            }
        }
    }

    /** Label a tree with the defaults for a highway tag for a particular country */
    protected abstract EnumMap<Node, Label> getTreeForHighway (String highway);

    /** Get a tree where every node is labeled unknown */
    protected EnumMap<Node, Label> getDefaultTree () {
        // TODO localize
        EnumMap<Node, Label> tree = new EnumMap<>(Node.class);

        // label all nodes as unknown
        for (Node n : Node.values()) {
            tree.put(n, Label.UNKNOWN);
        }

        return tree;
    }

    /** We are using such a small subset of the tree that we can just code it up manually here */
    protected enum Node {
        ACCESS, // root
        FOOT, VEHICLE, // level 1
        BICYCLE, CAR; // children of VEHICLE. CAR is shorthand for MOTOR_VEHICLE as we don't separately support hazmat carriers, mopeds, etc.

        public Node getParent () {
            switch (this) {
                case FOOT:
                case VEHICLE:
                    return ACCESS;
                case BICYCLE:
                case CAR:
                    return VEHICLE;
                default:
                    return null;
            }
        }

        public Node[] getChildren () {
            switch (this) {
                case ACCESS:
                    return new Node[] { FOOT, VEHICLE };
                case VEHICLE:
                    return new Node[] { BICYCLE, CAR };
                default:
                    return null;
            }
        }
    }

    /** What is the label of a particular node? */
    protected enum Label {
        YES, NO, UNKNOWN;

        public static Label fromTag (String tag) {
            tag = tag.toLowerCase().trim();
            // TODO customers, delivery should just be treated as no-thru-traffic
            if ("no".equals(tag) || "private".equals(tag) || "agricultural".equals(tag) || "delivery".equals(tag) ||
                    "dismount".equals(tag) || "forestry".equals(tag) || "customers".equals(tag))
                return NO;
            else if ("yes".equals("tag") || "permissive".equals(tag) || "designated".equals(tag))
                return YES;
            // TODO: no-thru-traffic, use_sidepath, customers, etc.
            else return UNKNOWN;

        }
    }
}
