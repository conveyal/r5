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
        //TODO: move to default permissions
        if (way.hasTag("railway", "platform") || way.hasTag("public_transport", "platform")) {
            tree.put(Node.FOOT, Label.YES);
            tree.put(Node.VEHICLE, Label.NO);
        }
        applySpecificPermissions(tree, way);

        EnumSet<EdgeStore.EdgeFlag> ret = EnumSet.noneOf(EdgeStore.EdgeFlag.class);

        if (walk(tree, Node.FOOT)) ret.add(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        if (walk(tree, Node.BICYCLE)) ret.add(EdgeStore.EdgeFlag.ALLOWS_BIKE);
        if (walk(tree, Node.CAR)) ret.add(EdgeStore.EdgeFlag.ALLOWS_CAR);

        // check for one-way streets. Note that leaf nodes will always be labeled and there is no unknown,
        // so we need not traverse the tree
        EnumMap<Node, OneWay> dir = getDirectionalTree(way);
        if (back) {
            // you can traverse back if this is not one way or it is one way reverse
            if (dir.get(Node.CAR) == OneWay.YES) ret.remove(EdgeStore.EdgeFlag.ALLOWS_CAR);
            if (dir.get(Node.BICYCLE) == OneWay.YES) ret.remove(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            if (dir.get(Node.FOOT) == OneWay.YES) ret.remove(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        }
        else {
            // you can always forward traverse unless this is a rare reversed one-way street
            if (dir.get(Node.CAR) == OneWay.REVERSE) ret.remove(EdgeStore.EdgeFlag.ALLOWS_CAR);
            if (dir.get(Node.BICYCLE) == OneWay.REVERSE) ret.remove(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            if (dir.get(Node.FOOT) == OneWay.REVERSE) ret.remove(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        }

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
    protected <T> void applyLabel(Node node, T label, EnumMap<Node, T> tree) {
        if (label instanceof Label && label.equals(Label.UNKNOWN))
            return;

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

    /** Get a directional tree (for use when labeling one-way streets) where every node is labeled bidirectional */
    private EnumMap<Node, OneWay> getDirectionalTree (Way way) {
        EnumMap<Node, OneWay> tree = new EnumMap<>(Node.class);

        // label all nodes as unknown
        for (Node n : Node.values()) {
            tree.put(n, OneWay.NO);
        }

        // some tags imply oneway = yes unless otherwise noted
        if (way.hasTag("highway", "motorway") || way.hasTag("junction", "roundabout"))
            applyLabel(Node.ACCESS, OneWay.YES, tree);

        // read the most generic tags first
        if (way.hasTag("oneway")) applyLabel(Node.ACCESS, OneWay.fromTag(way.getTag("oneway")), tree);
        if (way.hasTag("oneway:vehicle")) applyLabel(Node.VEHICLE, OneWay.fromTag(way.getTag("oneway:vehicle")), tree);
        if (way.hasTag("oneway:motorcar")) applyLabel(Node.CAR, OneWay.fromTag(way.getTag("oneway:motorcar")), tree);

        // one way specification for bicycles can be done in multiple ways
        if (way.hasTag("cycleway", "opposite") || way.hasTag("cycleway", "opposite_lane") || way.hasTag("cycleway", "opposite_track"))
            applyLabel(Node.BICYCLE, OneWay.NO, tree);

        if (way.hasTag("oneway:bicycle")) applyLabel(Node.BICYCLE, OneWay.fromTag(way.getTag("oneway:bicycle")), tree);

        // there are in fact one way pedestrian paths, believe it or not, but usually pedestrians don't inherit any oneway
        // restrictions from more general modes
        applyLabel(Node.FOOT, OneWay.NO, tree);
        if (way.hasTag("oneway:foot")) applyLabel(Node.FOOT, OneWay.fromTag(way.getTag("oneway")), tree);

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

    /** is a particular node one-way? http://wiki.openstreetmap.org/wiki/Key:oneway */
    protected enum OneWay {
        NO, YES, REVERSE;

        public static OneWay fromTag (String tag) {
            tag = tag.toLowerCase().trim();

            if ("yes".equals(tag) || "true".equals(tag) || "1".equals(tag))
                return YES;

            if ("-1".equals(tag) || "reverse".equals(tag))
                return REVERSE;

            return NO;
        }
    }
}
