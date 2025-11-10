package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Assign traversal permissions to edges based on their tags in OpenStreetMap.
 * see https://wiki.openstreetmap.org/wiki/Computing_access_restrictions#Algorithm
 * and also prior work by Marko Burjek:
 * https://github.com/buma/OpenTripPlanner-Maribor/blob/8eafa3ad9f1426877c6da3d730eaea46c6de35cf/src/main/java/org/opentripplanner/streets/permissions/AccessRestrictionsAlgorithm.java
 *
 * This class is abstract. You must make a country-specific subclass containing defaults for a particular country,
 * see https://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Access-Restrictions
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

    public RoadPermission getPermissions(Way way) {
        EnumMap<Node, Label> tree = getTreeForWay(way);

        applySpecificPermissions(tree, way);

        applyWheelchairPermissions(tree, way);

        EnumSet<EdgeStore.EdgeFlag> ret = EnumSet.noneOf(EdgeStore.EdgeFlag.class);

        Label walk = walk(tree, Node.FOOT);
        Label bicycle = walk(tree, Node.BICYCLE);
        Label car = walk(tree, Node.CAR);
        Label wheelchair = walk(tree, Node.WHEELCHAIR);
        if (walk == Label.YES) {
            ret.add(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        } else if (walk == Label.NO_THRU_TRAFFIC) {
            ret.add(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_PEDESTRIAN);
        }
        if (wheelchair == Label.YES) {
            ret.add(EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR);
        }

        if (wheelchair == Label.LIMITED) {
                ret.add(EdgeStore.EdgeFlag.LIMITED_WHEELCHAIR);
        }
        if (bicycle == Label.YES) {
            ret.add(EdgeStore.EdgeFlag.ALLOWS_BIKE);
        } else if (bicycle == Label.NO_THRU_TRAFFIC) {
            ret.add(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_BIKE);
        }
        if (car == Label.YES) {
            ret.add(EdgeStore.EdgeFlag.ALLOWS_CAR);
        } else if (car == Label.NO_THRU_TRAFFIC) {
            ret.add(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR);
        }


        EnumSet<EdgeStore.EdgeFlag> forward = EnumSet.copyOf(ret);
        EnumSet<EdgeStore.EdgeFlag> backward = EnumSet.copyOf(ret);

        applyDirectionalPermissions(way, forward, backward);

        // check for one-way streets. Note that leaf nodes will always be labeled and there is no unknown,
        // so we need not traverse the tree
        EnumMap<Node, OneWay> dir = getDirectionalTree(way);

        //Backward edge
        // you can traverse back if this is not one way or it is one way reverse
        if (dir.get(Node.CAR) == OneWay.YES) backward.remove(EdgeStore.EdgeFlag.ALLOWS_CAR);
        if (dir.get(Node.BICYCLE) == OneWay.YES) backward.remove(EdgeStore.EdgeFlag.ALLOWS_BIKE);
        if (dir.get(Node.FOOT) == OneWay.YES) backward.remove(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);

        //Forward edge
        // you can always forward traverse unless this is a rare reversed one-way street
        if (dir.get(Node.CAR) == OneWay.REVERSE) forward.remove(EdgeStore.EdgeFlag.ALLOWS_CAR);
        if (dir.get(Node.BICYCLE) == OneWay.REVERSE) forward.remove(EdgeStore.EdgeFlag.ALLOWS_BIKE);
        if (dir.get(Node.FOOT) == OneWay.REVERSE) forward.remove(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);

        //This needs to be called after permissions were removed in directionalTree
        //it is moved from directional tree since we can only remove permissions there
        //but they need to be added also for example highway=residential;bicycle=no;oneway=yes;bicycle:left=opposite_lane

        //If oneway is reversed (oneway=-1) opposite bicycle permission also need to be since they are opposite rest of traffic
        if (dir.get(Node.CAR) == OneWay.REVERSE){
            applyOppositeBicyclePermissions(way, forward);
        } else {
            applyOppositeBicyclePermissions(way, backward);
        }


        return new RoadPermission(forward, backward);
    }

    /**
     * Applies wheelchair permissions
     *
     * Removes wheelchair permissions on steps
     * and adds it on wheelchair=yes or ramp:wheelchair
     * wheelchair=limited gets special tag LIMITED and is currently non routable for wheelchairs
     * @param tree
     * @param way
     */
    private void applyWheelchairPermissions(EnumMap<Node, Label> tree, Way way) {
        if (way.hasTag("highway", "steps")) {
            applyLabel(Node.WHEELCHAIR, Label.NO, tree);
        }

        //TODO: what to do with wheelchair:limited currently it is routable for wheelchairs
        if (way.hasTag("wheelchair")) {
            applyLabel(Node.WHEELCHAIR, Label.fromTag(way.getTag("wheelchair")), tree);
        }
        if (way.hasTag("ramp:wheelchair")) {
            applyLabel(Node.WHEELCHAIR, Label.fromTag(way.getTag("ramp:wheelchair")), tree);
        }
    }

    /**
     *  Adds Allows bike to backward direction if one of those is true:
     *  - cycleway= starts with opposite (can be opposite_track or opposite_lane)
     *  - cycleway:left starts with opposite (can be opposite_track or opposite_lane)
     *  - cycleway:right starts with opposite (can be opposite_track or opposite_lane)
     * @param way
     * @param backward
     */
    private void applyOppositeBicyclePermissions(Way way, EnumSet<EdgeStore.EdgeFlag> backward) {
        String cyclewayLeftTagValue = way.getTag("cycleway:left");
        String cyclewayRightTagValue = way.getTag("cycleway:right");
        String cyclewayTagValue = way.getTag("cycleway");
        boolean addedBikePermissions = false;
        if (cyclewayTagValue != null && cyclewayTagValue.startsWith("opposite")) {
            backward.add(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            addedBikePermissions = true;
        } else if (cyclewayRightTagValue != null) {
            if (cyclewayRightTagValue.startsWith("opposite")){
                backward.add(EdgeStore.EdgeFlag.ALLOWS_BIKE);
                addedBikePermissions = true;
            }
        }
        if (!addedBikePermissions && cyclewayLeftTagValue != null) {
            if (cyclewayLeftTagValue.startsWith("opposite")) {
                backward.add(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            }
        }

    }

    /**
     * Adds cycleway permission of forward or backward edge if cycleway:left/:right has YES label
     *
     * AKA lane, track, shared_line
     * @param way
     * @param forward
     * @param backward
     */
    private void applyDirectionalPermissions(Way way, EnumSet<EdgeStore.EdgeFlag> forward,
        EnumSet<EdgeStore.EdgeFlag> backward) {
        String cyclewayLeftTagValue = way.getTag("cycleway:left");
        String cyclewayRightTagValue = way.getTag("cycleway:right");

        if (cyclewayRightTagValue != null) {
            Label cyclewayRight = Label.fromTag(cyclewayRightTagValue);
            if (cyclewayRight == Label.YES) {
                forward.add(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            }
        }

        if (cyclewayLeftTagValue != null) {
            Label cyclewayLeft = Label.fromTag(cyclewayLeftTagValue);
            if (cyclewayLeft == Label.YES) {
                backward.add(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            }
        }
    }

    @Deprecated
    public EnumSet<EdgeStore.EdgeFlag> getPermissions(Way way, boolean back) {
        RoadPermission roadPermission = getPermissions(way);
        if (back) {
            return roadPermission.backward;
        } else {
            return roadPermission.forward;
        }
    }

    /** returns whether this node is permitted traversal anywhere in the hierarchy */
    private Label walk (EnumMap<Node, Label> tree, Node node) {
        do {
            //We need to return first labeled node not first yes node
            //Otherwise access=yes bicycle=no returns true for bicycle
            if (tree.get(node) != Label.UNKNOWN)
                return tree.get(node);
        } while ((node = node.getParent()) != null);

        LOG.warn("Node label is unknown!!");
        //TODO: check this
        return Label.UNKNOWN;
    }

    /** apply any specific permissions that may exist */
    private void applySpecificPermissions (EnumMap<Node, Label> tree, Way way) {
        // start from the root of the tree
        if (way.hasTag("access")) applyLabel(Node.ACCESS, Label.fromTag(way.getTag("access")), tree);
        if (way.hasTag("foot")) applyLabel(Node.FOOT, Label.fromTag(way.getTag("foot")), tree);
        //Adds Walking permissions if street has any type of sidewalk (left,right,both)
        if (way.hasTag("sidewalk")) {
            String sidewalk = way.getTag("sidewalk");
            //For sidewalk direction doesn't matter since pedestrians can walk in both directions on sidewalk
            if ("both".equalsIgnoreCase(sidewalk) || "left".equalsIgnoreCase(sidewalk) || "right".equalsIgnoreCase(sidewalk)) {
                applyLabel(Node.FOOT, Label.YES, tree);
            }
        }
        if (way.hasTag("vehicle")) applyLabel(Node.VEHICLE, Label.fromTag(way.getTag("vehicle")), tree);
        if (way.hasTag("bicycle")) applyLabel(Node.BICYCLE, Label.fromTag(way.getTag("bicycle")), tree);
        if (way.hasTag("cycleway")) {
            Label label = Label.fromTag(way.getTag("cycleway"));
            //cycleway:no just means that there is no
            // cycleway on this road it doesn't mean it is forbidden to cycle there
            if (label != Label.NO) {
                applyLabel(Node.BICYCLE, label, tree);
            }
        }
        if (way.hasTag("cycleway:both")) applyLabel(Node.BICYCLE, Label.fromTag(way.getTag("cycleway:both")), tree);
        if (way.hasTag("motor_vehicle")) applyLabel(Node.CAR, Label.fromTag(way.getTag("motor_vehicle")), tree);
        // motorcar takes precedence over motor_vehicle
        if (way.hasTag("motorcar")) applyLabel(Node.CAR, Label.fromTag(way.getTag("motorcar")), tree);
    }

    /**  we want access=no to not override default cycling permissions, for example. */
    protected <T> void applyLabel(Node node, T label, EnumMap<Node, T> tree) {
        if (label instanceof Label && label.equals(Label.UNKNOWN))
            return;

        tree.put(node, label);
    }

    /** apply oneway hierarchically.  */
    private void applyOnewayHierarchically(Node node, OneWay oneWay, EnumMap<Node, OneWay> tree) {
        tree.put(node, oneWay);

        Node[] children = node.getChildren();

        if (children != null) {
            for (Node child : children) {
                applyOnewayHierarchically(child, oneWay, tree);
            }
        }
    }

    /** Label a sub-tree of modes with the defaults for a highway tag for a particular country */
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
     * This method is a bit stringly-typed since it's really accepting pipe- or semicolon-separated lists crammed
     * into Strings, but the tags are already being split on equals as well. Look at the places where this method is
     * called to see that this compromise leads to more readable code.
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
            applyOnewayHierarchically(Node.ACCESS, OneWay.YES, tree);

        // read the most generic tags first
        if (way.hasTag("oneway")) applyOnewayHierarchically(Node.ACCESS, OneWay.fromTag(way.getTag("oneway")), tree);
        if (way.hasTag("oneway:vehicle")) applyOnewayHierarchically(Node.VEHICLE, OneWay.fromTag(way.getTag("oneway:vehicle")), tree);
        if (way.hasTag("oneway:motorcar")) applyOnewayHierarchically(Node.CAR, OneWay.fromTag(way.getTag("oneway:motorcar")), tree);

        if (way.hasTag("oneway:bicycle")) applyOnewayHierarchically(Node.BICYCLE, OneWay.fromTag(way.getTag("oneway:bicycle")), tree);
        OneWay sidepath = OneWay.NO;
        if (way.hasTag("bicycle:forward")) {
            //Dismount, use_sidepath, no etc means only cycling in reverse direction
            if (Label.fromTag(way.getTag("bicycle:forward")) == Label.NO) {
                sidepath = OneWay.REVERSE;
            }
        }
        if (way.hasTag("bicycle:backward")) {
            //we can't cycle in backward direction same as if oneway:bicycle=yes
            //Label.NO is also if tag is use_sidepath or dismount
            if (Label.fromTag(way.getTag("bicycle:backward")) == Label.NO) {
                if (sidepath == OneWay.REVERSE) {
                    //Seems it is sidepath in both direction meaning cycling is completely disallowed
                    LOG.error(
                        "Way has tags bicycle:forward=use_sidepath and bicycle:backward=use_sidepath please use bicycle=use_sidepath");
                }
                sidepath = OneWay.YES;
            }
        }
        if (sidepath != OneWay.NO) {
            applyOnewayHierarchically(Node.BICYCLE, sidepath, tree);
        }

        // Usually pedestrians don't inherit any oneway restrictions from other modes.
        // However there are there are some one way pedestrian paths, e.g. entrances with turnstiles.
        // Most such tags are oneway:foot=no though.
        applyOnewayHierarchically(Node.FOOT, OneWay.NO, tree);
        if (way.hasTag("oneway:foot")) {
            applyOnewayHierarchically(Node.FOOT, OneWay.fromTag(way.getTag("oneway:foot")), tree);
        }

        return tree;
    }



    /**
     * We apply access permissions recursively to sub-trees of modes.
     * https://wiki.openstreetmap.org/wiki/Computing_access_restrictions#Transport_mode_hierarchy
     * We are using such a small subset of the tree on the wiki that we code up the parent/child relationships manually.
     */
    protected enum Node {
        ACCESS, // root
        FOOT, VEHICLE, // level 1
        WHEELCHAIR, //child of foot
        BICYCLE, CAR; // children of VEHICLE. CAR is shorthand for MOTOR_VEHICLE as we don't separately support hazmat carriers, mopeds, etc.

        public Node getParent () {
            switch (this) {
                case FOOT:
                case VEHICLE:
                    return ACCESS;
                case BICYCLE:
                case CAR:
                    return VEHICLE;
                case WHEELCHAIR:
                    return FOOT;
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
                case FOOT:
                    return new Node[] { WHEELCHAIR };
                default:
                    return null;
            }
        }
    }

    /** What is the label of a particular node? */
    protected enum Label {
        YES, NO, NO_THRU_TRAFFIC, UNKNOWN, LIMITED;

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
                || "permissive".equals(tag) || "designated".equals(tag)
                //cycleway lane on the street
                || "lane".equals(tag)
                //cycleway track next to the street
                || "track".equals(tag)
                //sharrow
                || "shared_lane".equals(tag)
                //special lane reserved for public transport on which cycling is allowed
                || "share_busway".equals(tag)
                //cycleway=crossing it should already have bicycle=yes from highway=cycleway or bicycle=yes
                || "crossing".equals(tag)
                ) {
                return YES;
            } else if (isTagFalse(tag) || tag.equals("license")
                || tag.equals("restricted") || tag.equals("prohibited")
                || tag.equals("emergency")
                || "use_sidepath".equals(tag) || "dismount".equals(tag)) {
                return NO;
            } else if (isNoThruTraffic(tag)) {
                return NO_THRU_TRAFFIC;
                //Used in setting opposite cycling permissions
            } else if (tag.startsWith("opposite")) {
                return UNKNOWN;
            } else if ("limited".equals(tag)) {
                return LIMITED;
            } else {
                LOG.debug("Unknown access tag:{}", tag);
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
