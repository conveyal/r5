package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Label edges with their traversal permissions, see https://wiki.openstreetmap.org/wiki/Computing_access_restrictions#Algorithm
 * and also prior work by Marko Burjek: https://github.com/buma/OpenTripPlanner-Maribor/blob/8eafa3ad9f1426877c6da3d730eaea46c6de35cf/src/main/java/org/opentripplanner/streets/permissions/AccessRestrictionsAlgorithm.java
 * Note that there are country-specific classes that contain defaults for particular countries, see https://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Access-Restrictions
 */
public abstract class TraversalPermissionLabeler {

    private static final Logger LOG = LoggerFactory.getLogger(TraversalPermissionLabeler.class);

    static Map<String, EnumMap<Node, Label>> defaultPermissions = new HashMap<>(30);

    //This is immutable map of highway tag and boolean which is true if this tag has same road with _link
    static final Map<String, Boolean> validHighwayTags;

    static {

        Map<String, Boolean> validHighwayTagsConst;
        validHighwayTagsConst = new HashMap<>(16);
        validHighwayTagsConst.put("motorway", true);
        validHighwayTagsConst.put("trunk", true);
        validHighwayTagsConst.put("primary", true);
        validHighwayTagsConst.put("secondary", true);
        validHighwayTagsConst.put("tertiary", true);
        validHighwayTagsConst.put("unclassified", false);
        validHighwayTagsConst.put("residential", false);
        validHighwayTagsConst.put("living_street", false);
        validHighwayTagsConst.put("road", false);
        validHighwayTagsConst.put("service", false);
        validHighwayTagsConst.put("track", false);
        validHighwayTagsConst.put("pedestrian", false);
        validHighwayTagsConst.put("path", false);
        validHighwayTagsConst.put("bridleway", false);
        validHighwayTagsConst.put("cycleway", false);
        validHighwayTagsConst.put("footway", false);
        validHighwayTagsConst.put("steps", false);
        validHighwayTagsConst.put("platform", false);
        validHighwayTagsConst.put("corridor", false); //Apparently indoor hallway
        validHighwayTags = Collections.unmodifiableMap(validHighwayTagsConst);

        addPermissions("motorway", "access=yes;bicycle=no;foot=no");
        addPermissions("trunk|primary|secondary|tertiary|unclassified|residential|living_street|road|service|track", "access=yes");
        addPermissions("pedestrian", "access=no;foot=yes");
        addPermissions("path", "access=no;foot=yes;bicycle=yes");
        addPermissions("bridleway", "access=no"); //horse=yes but we don't support horse
        addPermissions("cycleway", "access=no;bicycle=yes");
        addPermissions("footway|steps|platform|public_transport=platform|railway=platform|corridor", "access=no;foot=yes");
    }

    public EnumSet<EdgeStore.EdgeFlag> getPermissions(Way way, boolean back) {
        EnumMap<Node, Label> tree = getTreeForWay(way);

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
            //We need to return first labeled node not first yes node
            //Otherwise access=yes bicycle=no returns true for bicycle
            if (tree.get(node) != Label.UNKNOWN)
                return tree.get(node) == Label.YES;
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
    protected EnumMap<Node, Label> getTreeForWay (Way way) {
        String highway = way.getTag("highway");
        EnumMap<Node, Label> tree = getDefaultTree();
        if (highway != null) {
            highway = highway.toLowerCase().trim();
            EnumMap<Node, Label> defaultTree = defaultPermissions.getOrDefault("highway="+highway, defaultPermissions.get("highway=road"));
            tree.putAll(defaultTree);
            return tree;
        } else if (way.hasTag("railway", "platform")) {
            EnumMap<Node, Label> defaultTree = defaultPermissions.getOrDefault("railway=platform", defaultPermissions.get("highway=road"));
            tree.putAll(defaultTree);
            return tree;
        } else if(way.hasTag("public_transport", "platform")) {
            EnumMap<Node, Label> defaultTree = defaultPermissions
                .getOrDefault("public_transport=platform", defaultPermissions.get("highway=road"));
            tree.putAll(defaultTree);
            return tree;
        }

        return defaultPermissions.get("highway=road");
    }

    /**
     * For adding permissions for multiple tags.
     *
     * It splits tags on | and for each tag calls {@link #addPermission(String, String)}
     *
     * @param tags Tags are separated with | they can be specified with tag=value or just value where it is assumed that tag is highway.
     * @param traversalPermissions are separated with ; they are specified as tag=value
     */
    protected static void addPermissions(String tags, String traversalPermissions) {
        if (tags.contains("|")) {
            for (String specifier: tags.split("\\|")) {
               addPermission(specifier, traversalPermissions);
            }
        } else {
            addPermission(tags, traversalPermissions);
        }
    }

    /**
     * For adding permissions for one tag.
     *
     * It calls {@link #addTree(String, String)} once if highway doesn't have link counterpart.
     * Or twice if it has. AKA once for highway=motorway and once for highway=motorway_link.
     *
     * @param tag is specified as value where tag is assumed to be highway or tag=value
     * @param traversalPermissions are separated with ; they are specified as tag=value
     */
    private static void addPermission(String tag, String traversalPermissions) {
        if (tag.contains("\\|")) {
            throw new RuntimeException("Specifier shouldn't contain | please call addPermissions");
        }
        if (tag.contains("=")) {
            addTree(tag, traversalPermissions);
            return;
        }
        if (validHighwayTags.containsKey(tag)) {
            addTree("highway=" +tag, traversalPermissions);
            if (validHighwayTags.get(tag)) {
                addTree("highway=" +tag+"_link", traversalPermissions);
            }
        } else {
            LOG.warn("Tag \"{}\" is not valid highway tag!", tag);
        }
    }

    /**
     * Updates defaultPermissions with additional traversalPermissions
     *
     * @param tag is specified as tag=value
     * @param traversalPermissions are separated with ; they are specified as tag=value
     */
    private static void addTree(String tag, String traversalPermissions) {
        EnumMap<Node, Label> currentTree = defaultPermissions.getOrDefault(tag,new EnumMap<>(Node.class));
        String[] permissions = traversalPermissions.split(";");
        for (String permission : permissions) {
            String[] nodeLabel = permission.split("=");
            Node currentNode = Node.valueOf(nodeLabel[0].toUpperCase());
            Label currentLabel = Label.fromTag(nodeLabel[1]);
            if (currentLabel == Label.UNKNOWN) {
                throw new RuntimeException("permissions:" + permission + " invalid label for " + tag);
            }
            currentTree.put(currentNode, currentLabel);
        }
        defaultPermissions.put(tag, currentTree);
    }

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
        YES, NO, NO_THRU_TRAFFIC, UNKNOWN;

        private static boolean isTagTrue(String tagValue) {
            return ("yes".equals(tagValue) || "1".equals(tagValue) || "true".equals(tagValue));
        }

        private static boolean isTagFalse(String tagValue) {
            return ("no".equals(tagValue) || "0".equals(tagValue) || "false".equals(tagValue));
        }

        private static boolean isNoThruTraffic(String access) {
            return  "destination".equals(access)
                || "customers".equals(access) || "delivery".equals(access)
                || "forestry".equals(access)  || "agricultural".equals(access)
                || "residents".equals(access) || "resident".equals(access)
                || "customer".equals(access)
                || "private".equals(access) ;
        }

        public static Label fromTag (String tag) {
            //Some access tags are like designated;yes no idea why
            if (tag.contains(";")) {
                tag = tag.split(";")[0];
            }
            tag = tag.toLowerCase().trim();
            if (isTagTrue(tag) || "official".equals(tag)
                || "unknown".equals(tag) || "public".equals(tag)
                || "permissive".equals(tag) || "designated".equals(tag)) {
                return YES;
            } else if (isTagFalse(tag) || tag.equals("license")
                || tag.equals("restricted") || tag.equals("prohibited")
                || tag.equals("emergency")
                || "use_sidepath".equals(tag) || "dismount".equals(tag)) {
                return NO;
            } else if (isNoThruTraffic(tag)) {
                return YES; //FIXME: Temporary
            } else {
                LOG.info("Unknown access tag:{}", tag);
                return UNKNOWN;
            }
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
