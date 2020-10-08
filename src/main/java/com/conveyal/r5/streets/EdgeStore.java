package com.conveyal.r5.streets;

import com.conveyal.osmlib.Node;
import com.conveyal.r5.common.DirectionUtils;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.trove.AugmentedList;
import com.conveyal.r5.trove.TIntAugmentedList;
import com.conveyal.r5.trove.TLongAugmentedList;
import com.conveyal.r5.util.P2;
import com.conveyal.r5.util.TIntIntHashMultimap;
import com.conveyal.r5.util.TIntIntMultimap;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.list.TByteList;
import gnu.trove.list.TIntList;
import gnu.trove.list.TLongList;
import gnu.trove.list.TShortList;
import gnu.trove.list.array.TByteArrayList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.list.array.TShortArrayList;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.IntConsumer;

/**
 * This stores all the characteristics of the edges in the street graph layer of the transport network.
 * The naive way to do this is making one object per edge, and storing all those edges in one big list or in outgoing
 * and incoming edge lists in each vertex. However, representing the street graph as an object graph has some
 * disadvantages, including devoting a large amount of space to pointers, making serialization more complex, and
 * possible memory locality effects on speed.
 *
 * Originally we considered using a library that would simulate compound value types (C structs). A column store with
 * parallel arrays is better than a struct simulation because:
 * 1. it is less fancy, 2. it is auto-resizing (not fixed size), 3. I've tried both and they're the same speed.
 *
 * This problem will be solved when Java JEP 169 (value/inline types) finally becomes part of the language, especially
 * when inline types are available in generic types like ArrayList. The "record types" now in JVM preview would improve
 * readability and maintainability but are still reference classes, so not worth the migration.
 *
 * Edges come in pairs that have the same origin and destination vertices and the same geometries, but reversed.
 * Therefore many of the arrays are only half as big as the number of edges. All even numbered edges are forward, all
 * odd numbered edges are reversed.
 *
 * Typically, somewhat more than half of street segment edges have intermediate points (ie. their geometry contains
 * points other than the two endpoints). Therefore it's more efficient to add a complete dense column for
 * references to intermediate point arrays, instead of using a sparse HashMap to store values only for those edges that
 * have intermediate points.
 *
 * For geometry storage I tried several methods. Full Netherlands load in 4GB of heap:
 * Build time is around 8 minutes. 2.5-3GB was actually in use.
 * List of int arrays: 246MB serialized, 5.2 sec write, 6.3 sec read.
 * List of int arrays, full lists (not only intermediates): 261MB, 6.1 sec write, 6.2 sec read.
 * Indexes into single contiguous int array: 259MB, 5 sec write, 7.5 sec read.
 * We are using the first option as it is both readable and efficient.
 */
public class EdgeStore implements Serializable {

    public static final double STAIR_RELUCTANCE_FACTOR = 3.0;
    public static final double WALK_RELUCTANCE_FACTOR = 2.0;

    private static final Logger LOG = LoggerFactory.getLogger(EdgeStore.class);
    private static final short DEFAULT_SPEED_KPH = 50;
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    // The vertices that are referred to in these edges
    public VertexStore vertexStore;

    /** Boolean flags for every edge. Separate entries for forward and backward edges. */
    public TIntList flags;

    /**
     * CAR speeds, one speed for each edge. Separate entries for forward and backward edges.
     * rounded (m/s * 100) (2 decimal places)
     * Saved speed is mostly the same as speed saved as m/s * 1000 it differs in second decimal place and is 0.024% smaller
     * This way of saving speeds is 3.5% smaller then previous (saving 7 decimal places)
     */
    public TShortList speeds;

    /** The index of the origin vertex of each edge pair in the forward direction. One entry for each edge pair. */
    public TIntList fromVertices;

    /** The index of the destination vertex of each edge pair in the forward direction. One entry for each edge pair. */
    public TIntList toVertices;

    /** Length of the edge along its geometry (millimeters). One entry for each edge pair. */
    public TIntList lengths_mm;

    /** OSM ids of edges. One entry for each edge pair */
    public TLongList osmids;

    /**
     * Geometries. One entry for each edge pair. These are packed lists of lat, lon, lat, lon... as fixed-point
     * integers, and don't include the endpoints (i.e. don't include the intersection vertices, only intermediate points).
     */
    public List<int[]> geometries;

    /**
     * The compass angle at the start of the edge geometry (binary radians clockwise from North).
     * Internal representation is -180 to +179 integer degrees mapped to -128 to +127 (brads)
     * One entry for each edge pair.
     */
    public TByteList inAngles;

    /**
     * The compass angle at the end of the edge geometry (binary radians clockwise from North).
     * Internal representation is -180 to +179 integer degrees mapped to -128 to +127 (brads)
     * One entry for each edge pair.
     */
    public TByteList outAngles;

    /**
     * When applying scenarios, we don't duplicate the entire set of edges and vertices. We extend them, treating
     * the baseline graph as immutable. We have to be careful not to change or delete any elements of that baseline
     * graph which is shared between all threads. All edges at or above the index firstModifiableEdge can be modified,
     * all lower indexes should be treated as immutable.
     * All edges from firstModifiableEdge up to nEdges() are temporary and are in a separate spatial index.
     * This field also serves as an indication that a scenario is being applied and this EdgeStore is a protective copy,
     * whenever firstModifiableEdge &gt; 0.
     */
    public int firstModifiableEdge = 0;

    /**
     * When applying a scenario, we can't touch the baseline graph which is shared between all threads. Whenever we
     * must delete one of these immutable edges (e.g. when splitting a road to connect a new stop) we instead record
     * its index in temporarilyDeletedEdges, meaning it should be ignored as if it did not exist in this thread.
     * TODO: ignore these edges when routing, not only during spatial index queries.
     */
    public TIntSet temporarilyDeletedEdges = null;

    /**
     * This method will tell you whether a scenario has been applied to this EdgeStore, i.e. whether its lists have
     * been extended. There's one case where this method will fail: using Scenarios to create street networks from a
     * blank slate, where there are no edges before the scenario is applied.
     *
     * @return true if this EdgeStore has already been extended, and can therefore be modified without affecting the
     * baseline graph shared between all threads.
     */
    public boolean isExtendOnlyCopy() {
        // FIXME this definition will fail if we ever allow scenrios on top of completely empty street networks.
        return firstModifiableEdge > 0;
    }

    /** Turn restrictions for turning _out of_ each edge */
    public TIntIntMultimap turnRestrictions;

    /** Turn restrictions for turning _into_ each edge */
    public TIntIntMultimap turnRestrictionsReverse;

    /**
     * Stores and retrieves per-edge costs, either specified in input data or derived from standard OSM tags.
     * For now this may be null, indicating that no per-edge times are available and default values should be used.
     */
    public EdgeTraversalTimes edgeTraversalTimes;

    /** The street layer of a transport network that the edges in this edgestore make up. */
    public StreetLayer layer;

    /** As a convenience, the set of all edge flags that control which modes can traverse the edge */
    public static final transient EnumSet<EdgeFlag> PERMISSION_FLAGS = EnumSet
        .of(EdgeFlag.ALLOWS_PEDESTRIAN, EdgeFlag.ALLOWS_BIKE, EdgeFlag.ALLOWS_CAR);

    /**
     * When we create new edges, we must assign them OSM IDs. By convention user-generated OSM IDs not in the main
     * OSM database are negative.
     * FIXME it's weird to store a positive ID, increment it in a positive direction, but always negate it before using it.
     */
    private long generatedOSMID = 1;

    public EdgeStore (VertexStore vertexStore, StreetLayer layer, int initialSize) {
        this.vertexStore = vertexStore;
        this.layer = layer;

        // There are separate flags and speeds entries for the forward and backward edges in each pair.
        flags = new TIntArrayList(initialSize);
        speeds = new TShortArrayList(initialSize);
        // Vertex indices, geometries, and lengths are shared between pairs of forward and backward edges.
        int initialEdgePairs = initialSize / 2;
        fromVertices = new TIntArrayList(initialEdgePairs);
        toVertices = new TIntArrayList(initialEdgePairs);
        geometries = new ArrayList<>(initialEdgePairs);
        lengths_mm = new TIntArrayList(initialEdgePairs);
        osmids = new TLongArrayList(initialEdgePairs);
        inAngles = new TByteArrayList(initialEdgePairs);
        outAngles = new TByteArrayList(initialEdgePairs);
        turnRestrictions = new TIntIntHashMultimap();
        turnRestrictionsReverse = new TIntIntHashMultimap();
        edgeTraversalTimes = null;
    }

    /**
     * Edge flags are various boolean values (requiring a single bit of information) that can be attached to each
     * edge in the street graph. They are each assigned a bit number from 0...31 so they can all be packed into a
     * single integer field for space and speed reasons.
     */
    public enum EdgeFlag {

        // Street categories.
        // FIXME some street categories are mutually exclusive and should not be flags, just narrow numbers.
        // This could be done with static utility functions that write the low N bits of a number into a given position.
        // Maybe reserve the first 4-5 bits (or a whole byte, and 16 bits for flags) for mutually exclusive edge types.
        UNUSED (0), // This flag is deprecated and currently unused. Use it for something new and interesting!
        BIKE_PATH (1),
        SIDEWALK (2),
        CROSSING (3),
        ROUNDABOUT (4),
        ELEVATOR (5),
        STAIRS (6),
        PLATFORM (7),
        BOGUS_NAME (8),
        NO_THRU_TRAFFIC (9),
        NO_THRU_TRAFFIC_PEDESTRIAN (10),
        NO_THRU_TRAFFIC_BIKE (11),
        NO_THRU_TRAFFIC_CAR (12),
        SLOPE_OVERRIDE (13),
        /** An edge that links a transit stop to the street network; two such edges should not be traversed consecutively. */
        LINK (14),

        // Permissions
        ALLOWS_PEDESTRIAN (15),
        ALLOWS_BIKE (16),
        ALLOWS_CAR (17),
        ALLOWS_WHEELCHAIR (18),
        //Set when OSM tags are wheelchair==limited currently unroutable
        LIMITED_WHEELCHAIR(19),

        // If this flag is present, the edge is good idea to use for linking. Excludes runnels, motorways, and covered roads.
        LINKABLE(20),

        // The highest five bits of the flags field represent the bicycle level of traffic stress (LTS) for this street.
        // See http://transweb.sjsu.edu/PDFs/research/1005-low-stress-bicycling-network-connectivity.pdf
        // The comments below on the LTS flag for each level 1 through 4 are pasted from that document.
        // FIXME bicycle LTS should not really be flags, the categories are mutually exclusive and can be stored in 2 bits.

        /**
         * If this flag is set, then its LTS has been loaded from OSM tags and we will not apply further processing to
         * infer it. We jump to flag number 27 here to group all five LTS flags together at the high end of the 32-bit
         * integer flags field.
         */
        BIKE_LTS_EXPLICIT (27),

        /**
         * Presenting little traffic stress and demanding little attention from cyclists, and attractive enough for a
         * relaxing bike ride. Suitable for almost all cyclists, including children trained to safely cross intersections.
         * On links, cyclists are either physically separated from traffic, or are in an exclusive bicycling zone next to
         * a slow traffic stream with no more than one lane per direction, or are on a shared road where they interact
         * with only occasional motor vehicles (as opposed to a stream of traffic) with a low speed differential. Where
         * cyclists ride alongside a parking lane, they have ample operating space outside the zone into which car
         * doors are opened. Intersections are easy to approach and cross.
         */
        BIKE_LTS_1 (28),

        /**
         * Presenting little traffic stress and therefore suitable to most adult cyclists but demanding more attention
         * than might be expected from children. On links, cyclists are either physically separated from traffic, or are
         * in an exclusive bicycling zone next to a well-confined traffic stream with adequate clearance from a parking
         * lane, or are on a shared road where they interact with only occasional motor vehicles (as opposed to a
         * stream of traffic) with a low speed differential. Where a bike lane lies between a through lane and a rightturn
         * lane, it is configured to give cyclists unambiguous priority where cars cross the bike lane and to keep
         * car speed in the right-turn lane comparable to bicycling speeds. Crossings are not difficult for most adults.
         */
        BIKE_LTS_2 (29),

        /**
         * More traffic stress than LTS 2, yet markedly less than the stress of integrating with multilane traffic, and
         * therefore welcome to many people currently riding bikes in American cities. Offering cyclists either an
         * exclusive riding zone (lane) next to moderate-speed traffic or shared lanes on streets that are not multilane
         * and have moderately low speed. Crossings may be longer or across higher-speed roads than allowed by
         * LTS 2, but are still considered acceptably safe to most adult pedestrians.
         */
        BIKE_LTS_3 (30),

        /**
         * A level of stress beyond LTS3. (this is in fact the official definition. -Ed.)
         * Also known as FLORIDA_AVENUE.
         */
        BIKE_LTS_4 (31);

        /**
         * In each enum value this field should contain an integer with only a single bit switched on (a power of two).
         */
        public final int flag;

        /** Conveniently create a unique integer flag pattern for each of the enum values. */
        private EdgeFlag (int bitNumber) {
            flag = 1 << bitNumber;
        }

    }

    /**
     * This creates the bare topological edge pair with a length.
     * Flags, detailed geometry, etc. must be set subsequently using an edge cursor.
     * This avoids having a tangle of different edge creator functions for different circumstances.
     * @return a cursor pointing to the forward edge in the pair, which always has an even index.
     */
    public Edge addStreetPair(int beginVertexIndex, int endVertexIndex, int edgeLengthMillimeters, long osmID) {
        // The first new edge to be created will have an index equal to the number of existing edges.
        int forwardEdgeIndex = nEdges();
        // The caller supplies an OSM ID less than 0 to indicate that the edges being created are not derived
        // from any OSM way. A unique negative OSM ID is generated.
        // By convention, OSM IDs less than 0 represent ways not in the main public OSM database.
        if (osmID < 0) {
            osmID = -generatedOSMID;
            generatedOSMID++;
        }

        if (beginVertexIndex < 0 || beginVertexIndex >= vertexStore.getVertexCount()) {
            throw new IllegalArgumentException(String.format("Attempt to begin edge pair at nonexistent vertex %s", beginVertexIndex));
        }

        if (endVertexIndex < 0 || endVertexIndex >= vertexStore.getVertexCount()) {
            throw new IllegalArgumentException(String.format("Attempt to end edge pair at nonexistent vertex %s", endVertexIndex));
        }

        // Extend the parallel lists in the EdgeStore to hold the values for the new edge pair.
        // Store only one length, set of endpoints, and intermediate geometry per pair of edges.
        lengths_mm.add(edgeLengthMillimeters);
        fromVertices.add(beginVertexIndex);
        toVertices.add(endVertexIndex);
        geometries.add(EMPTY_INT_ARRAY);
        osmids.add(osmID);
        inAngles.add((byte) 0);
        outAngles.add((byte)0);

        // Speed and flags are stored separately for each edge in a pair (unlike length, geom, etc.)

        // Forward edge.
        // No speed or flags are set, they must be set afterward using the edge cursor.
        speeds.add(DEFAULT_SPEED_KPH);
        flags.add(0);

        // Backward edge.
        // No speed or flags are set, they must be set afterward using the edge cursor.
        speeds.add(DEFAULT_SPEED_KPH);
        flags.add(0);

        if (edgeTraversalTimes != null) {
            edgeTraversalTimes.addOneEdge();
            edgeTraversalTimes.addOneEdge();
        }

        Edge edge = getCursor(forwardEdgeIndex);
        //angles needs to be calculated here since some edges don't get additional geometry (P+R, transit, etc.)
        edge.calculateAngles();
        return edge;
    }

    /**
     * Sets turn restriction maps in state
     * @param streetMode of previous state (since turn restrictions are set only in CAR mode)
     * @param reverseSearch if this is reverse search
     * @param s1 new state
     */
    void startTurnRestriction(StreetMode streetMode, boolean reverseSearch, StreetRouter.State s1) {
        if (reverseSearch) {
            // add turn restrictions that start on this edge
            // Turn restrictions only apply to cars for now. This is also coded in canTurnFrom, so change it both places
            // if/when it gets changed.
            if (streetMode == StreetMode.CAR && turnRestrictionsReverse.containsKey(s1.backEdge)) {
                if (s1.turnRestrictions == null)
                    s1.turnRestrictions = new TIntIntHashMap();
                turnRestrictionsReverse.get(s1.backEdge).forEach(r -> {
                    s1.turnRestrictions.put(r, 1); // we have traversed one edge
                    return true; // continue iteration
                });
                //LOG.info("RRTADD: S1:{}|{}", s1.backEdge, s1.turnRestrictions);
            }
        } else {
            // add turn restrictions that start on this edge
            // Turn restrictions only apply to cars for now. This is also coded in canTurnFrom, so change it both places
            // if/when it gets changed.
            if (streetMode == StreetMode.CAR && turnRestrictions.containsKey(s1.backEdge)) {
                if (s1.turnRestrictions == null)
                    s1.turnRestrictions = new TIntIntHashMap();
                turnRestrictions.get(s1.backEdge).forEach(r -> {
                    s1.turnRestrictions.put(r, 1); // we have traversed one edge
                    return true; // continue iteration
                });
                //LOG.info("TADD: S1:{}|{}", s1.backEdge, s1.turnRestrictions);
            }
        }
    }

    /**
     * Inner class that serves as a cursor: points to a single edge in this store, and can be moved to other indexes.
     * TODO make this a separate class so the outer class reference is explicit (useful in copy functions)
     */
    public class Edge {

        int edgeIndex = -1;
        int pairIndex = -1;
        boolean isBackward = true;

        /**
         * Move the cursor forward one edge.
         * @return true if we have not advanced past the end of the list (there is an edge at the new position).
         */
        public boolean advance() {
            edgeIndex += 1;
            pairIndex = edgeIndex / 2;
            isBackward = !isBackward;
            return edgeIndex < nEdges();
        }

        /** Move the cursor back one edge. */
        public boolean retreat () {
            edgeIndex--;
            pairIndex = edgeIndex / 2;
            isBackward = !isBackward;
            return edgeIndex >= 0;
        }

        /** Jump to a specific edge number. */
        public void seek(int pos) {
            if (pos < 0)
                throw new ArrayIndexOutOfBoundsException("Attempt to seek to negative edge number");
            if (pos >= nEdges())
                throw new ArrayIndexOutOfBoundsException("Attempt to seek beyond end of edge store");

            edgeIndex = pos;
            // divide and multiply by two are fast bit shifts
            pairIndex = edgeIndex / 2;
            isBackward = (pairIndex * 2) != edgeIndex;
        }

        public int getFromVertex() {
            return isBackward ? toVertices.get(pairIndex) : fromVertices.get(pairIndex);
        }

        public int getToVertex() {
            return isBackward ? fromVertices.get(pairIndex) : toVertices.get(pairIndex);
        }

        /**
         * NOTE that this will have an effect on both edges in the bidirectional edge pair.
         */
        public void setToVertex(int toVertexIndex) {
            if (isBackward) {
                fromVertices.set(pairIndex, toVertexIndex);
            } else {
                toVertices.set(pairIndex, toVertexIndex);
            }
        }

        public boolean getFlag(EdgeFlag flag) {
            return (flags.get(edgeIndex) & flag.flag) != 0;
        }

        public void setFlag(EdgeFlag flag) {
            flags.set(edgeIndex, flags.get(edgeIndex) | flag.flag);
        }

        public void clearFlag(EdgeFlag flag) {
            flags.set(edgeIndex, flags.get(edgeIndex) & ~(flag.flag)); // TODO verify logic
        }

        /**
         * This function should not exist. This is a hack until we stop using an inner class and it's possible to
         * access the outer EdgeStore field from Edge functions.
         */
        public EdgeStore getEdgeStore() {
            return EdgeStore.this;
        }

        /**
         * This is inefficient, it's just a stopgap until we refactor to eliminate the need for Sets of EdgeFlag
         */
        public EnumSet<EdgeFlag> getFlags() {
            EnumSet<EdgeFlag> ret = EnumSet.noneOf(EdgeFlag.class);
            for (EdgeFlag flag : EdgeFlag.values()) {
                if (getFlag(flag)) {
                    ret.add(flag);
                }
            }
            return ret;
        }

        public void setFlags(Set<EdgeFlag> flagSet) {
            for (EdgeFlag flag : flagSet) {
                setFlag(flag);
            }
        }

        public short getSpeed() {
            return speeds.get(edgeIndex);
        }

        /**
         * @return the car speed on this edge, taking live traffic updates into account if requested (though that's not
         * yet implemented)
         */
        public float getCarSpeedMetersPerSecond() {
            return (float) ((speeds.get(edgeIndex) / 100.));
        }

        public float getSpeedKph () {
            return (float) ((speeds.get(edgeIndex) / 100.) * 3.6);
        }

        // This is really ugly, we should store speeds in mm/sec or something. There is an outstanding PR for this.
        public void setSpeedKph (double speedKph) {
            speeds.set(edgeIndex, (short)(speedKph / 3.6 * 100));
        }

        /** Note, this is expecting weird units and should be hidden. Use setSpeedKph. */
        public void setSpeed(short speed) {
            speeds.set(edgeIndex, speed);
        }

        public int getLengthMm () {
            return lengths_mm.get(pairIndex);
        }

        /**
         * Copy the flags and speeds from the supplied Edge cursor into this one.
         * Doesn't copy OSM ids since those are set when edge is created.
         * This is a hack and should be done some other way.
         */
        public void copyPairFlagsAndSpeeds(Edge other) {
            int foreEdge = pairIndex * 2;
            int backEdge = foreEdge + 1;
            int otherForeEdge = other.pairIndex * 2;
            int otherBackEdge = otherForeEdge + 1;
            flags.set(foreEdge, other.getEdgeStore().flags.get(otherForeEdge));
            flags.set(backEdge, other.getEdgeStore().flags.get(otherBackEdge));
            speeds.set(foreEdge, other.getEdgeStore().speeds.get(otherForeEdge));
            speeds.set(backEdge, other.getEdgeStore().speeds.get(otherBackEdge));
        }

        public void copyPairGeometry(Edge other) {
            geometries.set(pairIndex, other.getEdgeStore().geometries.get(other.pairIndex));
        }

        /**
         * @return length of edge in meters
         */
        public double getLengthM() {
            return getLengthMm() / 1000.0;
        }

        /**
         * Set the length for the current edge pair (always the same in both directions).
         */
        public void setLengthMm (int millimeters) {
            lengths_mm.set(pairIndex, millimeters);
        }

        public boolean isBackward () {
            return isBackward;
        }

        public boolean isForward () {
            return !isBackward;
        }

        /**
         * Calculate the speed appropriately given the ProfileRequest and traverseMode and the current wall clock time.
         * Note: this is not strictly symmetrical, because in a forward search we get the speed based on the
         * time we enter this edge, whereas in a reverse search we get the speed based on the time we exit
         * the edge.
         *
         * If driving speed is based on max edge speed. (or from traffic if traffic is supported)
         *
         * Otherwise speed is based on wanted walking, cycling speed provided in ProfileRequest.
         */
        public float calculateSpeed(ProfileRequest options, StreetMode traverseStreetMode) {
            if (traverseStreetMode == null) {
                // Do we really want to do this? Why not just let the NPE happen if the parameter is missing?
                return Float.NaN;
            } else if (traverseStreetMode == StreetMode.CAR) {
                // TODO: apply speed based on traffic information if switched on in the request
                return getCarSpeedMetersPerSecond();
            }
            return options.getSpeedForMode(traverseStreetMode);
        }

        // TODO move most or all of this logic into the router to avoid passing search state like req and timeCalculator
        public StreetRouter.State traverse (
                StreetRouter.State s0,
                StreetMode streetMode,
                ProfileRequest req,
                TraversalTimeCalculator timeCalculator
        ) {
            // The vertex we'll be at after the traversal
            // TODO check/assert that s0 is at the other vertex, at the other end of the edge.

            if (temporarilyDeletedEdges != null && temporarilyDeletedEdges.contains(edgeIndex)) {
                return null;
            }

            int vertex;
            if (req.reverseSearch) {
                vertex = getFromVertex();
            } else {
                vertex = getToVertex();
            }

            // A new instance representing the state after traversing this edge. Fields will be filled in later.
            StreetRouter.State s1 = new StreetRouter.State(vertex, edgeIndex, s0);

            if (!canTurnFrom(s0, s1, req.reverseSearch)) {
                return null;
            }

            // Null out turn restrictions if they're empty
            if (s1.turnRestrictions != null && s1.turnRestrictions.isEmpty()) {
                s1.turnRestrictions = null;
            }
            startTurnRestriction(s0.streetMode, req.reverseSearch, s1);

            //We allow two links in a row if this is a first state (negative back edge or no backState
            //Since at least P+R stations are connected to graph with only LINK edges and otherwise search doesn't work
            //Since backEdges are set from first part of multipart P+R search
            if ((s0.backEdge >=0 ) && (s0.backState != null) && getFlag(EdgeFlag.LINK) && getCursor(s0.backEdge).getFlag(EdgeFlag.LINK)) {
                // two link edges in a row, in other words a shortcut. Disallow this.
                return null;
            }

            // Check whether this edge allows the selected mode, considering the request settings.
            if (streetMode == StreetMode.WALK) {
                if (!getFlag(EdgeFlag.ALLOWS_PEDESTRIAN)) {
                    return null;
                }
                if (req.wheelchair && !getFlag(EdgeFlag.ALLOWS_WHEELCHAIR)) {
                    return null;
                }
            } else if (streetMode == StreetMode.BICYCLE) {
                // If biking is not allowed on this edge, or if the traffic stress is too high, walk the bike.
                boolean tryWalking = !getFlag(EdgeFlag.ALLOWS_BIKE);
                if (req.bikeTrafficStress > 0 && req.bikeTrafficStress < 4) {
                    if (getFlag(EdgeFlag.BIKE_LTS_4)) tryWalking = true;
                    if (req.bikeTrafficStress < 3 && getFlag(EdgeFlag.BIKE_LTS_3)) tryWalking = true;
                    if (req.bikeTrafficStress < 2 && getFlag(EdgeFlag.BIKE_LTS_2)) tryWalking = true;
                }
                if (tryWalking) {
                    if (!getFlag(EdgeFlag.ALLOWS_PEDESTRIAN)) {
                        return null;
                    }
                    streetMode = StreetMode.WALK;
                }
            } else if (streetMode == StreetMode.CAR) {
                if (!getFlag(EdgeFlag.ALLOWS_CAR)) {
                    return null;
                }
            }

            s1.streetMode = streetMode;
            int traverseTimeSeconds = timeCalculator.traversalTimeSeconds(this, streetMode, req);

            // This was rounding up, now truncating ... maybe change back for consistency?
            // int roundedTime = (int) Math.ceil(time);

            int turnTimeSeconds = 0;
            // Negative backEdge means this state is not the result of traversing an edge (it's the start of a search).
            if (s0.backEdge >= 0) {
                if (req.reverseSearch) {
                    turnTimeSeconds = timeCalculator.turnTimeSeconds(getEdgeIndex(), s0.backEdge, streetMode);
                } else {
                    turnTimeSeconds = timeCalculator.turnTimeSeconds(s0.backEdge, getEdgeIndex(), streetMode);
                }
            }
            // TODO add checks for negative increment values to these functions.
            s1.incrementTimeInSeconds(traverseTimeSeconds + turnTimeSeconds);
            s1.distance += getLengthMm();

            // Make sure we don't have states that don't increment weight/time, otherwise we could create loops.
            // This should not happen since we always round upward (ceil function), perhaps replace with assertions.
            if (s1.durationSeconds == s0.durationSeconds) {
                s1.incrementTimeInSeconds(1);
            }
            if (s1.distance == s0.distance) {
                s1.distance += 1;
            }
            return s1;
        }

        /** Can we turn onto this edge from this state? Also copies still-applicable restrictions forward. */
        public boolean canTurnFrom(StreetRouter.State s0, StreetRouter.State s1, boolean reverseSearch) {
            // Turn restrictions only apply to cars for now. This is also coded in traverse, so change it both places
            // if/when it gets changed.
            if (s0.turnRestrictions != null && s0.streetMode == StreetMode.CAR) {
                // clone turn restrictions
                s1.turnRestrictions = new TIntIntHashMap(s0.turnRestrictions);

                RESTRICTIONS: for (TIntIntIterator it = s1.turnRestrictions.iterator(); it.hasNext();) {
                    it.advance();
                    int ridx = it.key();
                    TurnRestriction restriction = layer.turnRestrictions.get(ridx);

                    // check via ways if applicable
                    // subtract 1 because the first (fromEdge) is not a via edge
                    int posInRestriction = it.value() - 1;
                    int toEdge = restriction.toEdge;
                    int[] viaEdges = restriction.viaEdges;

                    if (reverseSearch) {
                        //In reverse search order of from/to and viaEdges is changed since we search from toEdge to fromEdge
                        toEdge = restriction.fromEdge;
                        if (viaEdges.length > 1) {
                            TurnRestriction.reverse(viaEdges);
                        }
                    }

                    if (posInRestriction < viaEdges.length) {
                        if (s1.backEdge != restriction.viaEdges[posInRestriction]) {
                            // we have exited the restriction
                            if (restriction.only) return false;
                            else {
                                it.remove(); // no need to worry about this one anymore
                                continue RESTRICTIONS;
                            }
                        }
                        else {
                            // increment position
                            it.setValue(it.value() + 1);
                        }
                    }
                    else {
                        if (toEdge != s1.backEdge) {
                            // we have exited the restriction
                            if (restriction.only)
                                return false;
                            else {
                                it.remove();
                                continue RESTRICTIONS;
                            }
                        } else {
                            if (!restriction.only)
                                return false;
                            else {
                                it.remove(); // done with this restriction
                                continue RESTRICTIONS;
                            }
                        }
                    }
                }
            }
            return true;
        }

        /**
         * Cut the packed array of intermediate coordinates in two at the specified segment index, non-destructively
         * (i.e. returning copies). The original array of the edge's intermediate coordinates does not include the first
         * and last points of the edge (which are supplied by vertices), so the segment at index N ends at
         * intermediate point N, which occupies array indexes N2 and N2+1 in the packed array. Intermediate points
         * before the split point (which is on the specified segment), ending with the beginning of the split
         * segment, should be in the first return array. Intermediate points after the split point, starting with the
         * end of the split segment, should be in the second return array. The first or second return array can be
         * empty if we are splitting at the first or last segment respectively, or if there are no intermediate
         * coordinates.
         * @return Pair with element a being int[] of intermediate coordinates before split, and element b being
         * int[] of intermediate coordinates after split.
         */
        public P2<int[]> splitGeometryAfter(int segment) {
            int[] preSplit = EMPTY_INT_ARRAY;
            int[] postSplit = EMPTY_INT_ARRAY;
            // Original packed array of edge's intermediate coordinates
            int[] original = geometries.get(pairIndex);
            if (original.length > 0) {
                if (segment > 0) {
                    preSplit = Arrays.copyOfRange(original, 0, segment * 2);
                }
                if (segment * 2 < original.length) {
                    postSplit = Arrays.copyOfRange(original, segment * 2, original.length);
                }
            }
            return new P2<>(preSplit, postSplit);
        }

        /**
         * Set intermediate coordinates directly with packed array
         * @param coordinates Packed lists of lat, lon, lat, lon... as fixed-point integers
         */
        public void setGeometry (int[] coordinates) {
            geometries.set(pairIndex, coordinates);
            calculateAngles();
        }

        /**
         * Set intermediate coordinates from OSM nodes
         */
        public void setGeometry (List<Node> nodes) {
            // The same empty int array represents all straight-line edges.
            if (nodes.size() <= 2) {
                geometries.set(pairIndex, EMPTY_INT_ARRAY);
                //Angles also need to be calculated here since when splitting edges some edges currently loose geometry
                //and otherwise angles would be incorrect
                calculateAngles();
                return;
            }
            if (isBackward) {
                LOG.warn("Setting a forward geometry on a back edge.");
            }
            // Create a geometry, which will be used for both forward and backward edge.
            int nIntermediatePoints = nodes.size() - 2;
            // Make a packed list of all coordinates between the endpoint intersections.
            int[] intermediateCoords = new int[nIntermediatePoints * 2];
            int i = 0;
            for (Node node : nodes.subList(1, nodes.size() - 1)) {
                intermediateCoords[i++] = node.fixedLat;
                intermediateCoords[i++] = node.fixedLon;
            }
            geometries.set(pairIndex, intermediateCoords);

            //This is where angles for most edges is calculated
            calculateAngles();

        }

        /**
         * Reads edge geometry and calculates in and out angle
         *
         * Angles are saved as binary radians clockwise from North.
         * 0 is 0
         * -180° = -128
         * 180° = -180° = -128
         * 178° = 127
         *
         * First and last angles are calculated between first/last and point that is at
         * least 10 m from it according to line distance
         *
         * @see DirectionUtils
         *
         * TODO: calculate angles without converting to the Linestring and back
         */
        private void calculateAngles() {
            LineString geometry = getGeometry();

            byte inAngleRad = DirectionUtils.getFirstAngleBrads(geometry);
            inAngles.set(pairIndex, inAngleRad);

            byte outAngleRad = DirectionUtils.getLastAngleBrads(geometry);
            outAngles.set(pairIndex, outAngleRad);
        }

        public int getOutAngle() {
            int angle;
            if (isBackward()) {
                angle  = DirectionUtils.bradsToDegree((byte)(inAngles.get(pairIndex)-DirectionUtils.m180));
                return angle;
            } else {
                angle  = DirectionUtils.bradsToDegree(outAngles.get(pairIndex));
                return angle;
            }
        }

        public int getInAngle() {
            int angle;
            if (isBackward()) {
                angle = DirectionUtils.bradsToDegree((byte)(outAngles.get(pairIndex)-DirectionUtils.m180));
                return angle;
            } else {
                angle = DirectionUtils.bradsToDegree(inAngles.get(pairIndex));
                return angle;
            }
        }


        /**
         * Returns LineString geometry of edge
         * Uses from/to vertices for first/last node and nodes from geometries for middle nodes
         *
         * TODO: it might be better idea to return just list of coordinates
         * @return
         */
        public LineString getGeometry() {
            int[] coords = geometries.get(pairIndex);
            //Size is 2 (from and to vertex) if there are no intermediate vertices
            int size = coords == EMPTY_INT_ARRAY ? 2 :
                //division with two since coordinates are in same array saved as lat, lon,lat etc.
                (coords.length / 2) + 2;
            Coordinate[] c = new Coordinate[size];

            VertexStore.Vertex fromVertex = vertexStore.getCursor(getFromVertex());
            VertexStore.Vertex toVertex = vertexStore.getCursor(getToVertex());

            double fromVertexLon = fromVertex.getLon();
            double fromVertexLat = fromVertex.getLat();
            double toVertexLon = toVertex.getLon();
            double toVertexLat = toVertex.getLat();

            boolean reverse = isBackward();

            double firstCoorLon = reverse ? toVertexLon : fromVertexLon;
            double firstCoorLat = reverse ? toVertexLat : fromVertexLat;
            double lastCoorLon = reverse ? fromVertexLon : toVertexLon;
            double lastCoorLat = reverse ? fromVertexLat : toVertexLat;

            // NB it initially appears that we are reversing the from and to vertex twice, but keep in mind that
            // getFromVertex returns the from vertex of the edge _pair_ rather than that of a particular edge.
            // This is the from vertex when we are on a forward edge, and the to vertex when we are on a back edge.
            c[0] = new Coordinate(firstCoorLon, firstCoorLat);
            if (coords != null) {
                for (int i = 1; i < c.length - 1; i++) {
                    int ilat = coords[(i - 1) * 2];
                    int ilon = coords[(i - 1) * 2 + 1];
                    c[i] = new Coordinate(ilon / VertexStore.FIXED_FACTOR, ilat /  VertexStore.FIXED_FACTOR);
                }
            }
            c[c.length - 1] = new Coordinate(lastCoorLon, lastCoorLat);
            LineString out = GeometryUtils.geometryFactory.createLineString(c);
            if (reverse)
                out = (LineString) out.reverse();
            return out;
        }

        /**
         * Call a function on every segment in this edge's geometry.
         * Always iterates forward over the geometry, whether we are on a forward or backward edge.
         */
        public void forEachSegment (SegmentConsumer segmentConsumer) {
            VertexStore.Vertex vertex = vertexStore.getCursor(fromVertices.get(pairIndex));
            int prevFixedLat = vertex.getFixedLat();
            int prevFixedLon = vertex.getFixedLon();
            int[] intermediates = geometries.get(pairIndex);
            int s = 0;
            int i = 0;
            while (i < intermediates.length) {
                int fixedLat = intermediates[i++];
                int fixedLon = intermediates[i++];
                segmentConsumer.consumeSegment(s, prevFixedLat, prevFixedLon, fixedLat, fixedLon);
                prevFixedLat = fixedLat;
                prevFixedLon = fixedLon;
                s++;
            }
            vertex.seek(toVertices.get(pairIndex));
            segmentConsumer.consumeSegment(s, prevFixedLat, prevFixedLon, vertex.getFixedLat(), vertex.getFixedLon());
        }


        /**
         * Call a function for every point on this edge's geometry, including the beginning end end points.
         * Always iterates forward over the geometry, whether we are on a forward or backward edge.
         */
        public void forEachPoint (PointConsumer pointConsumer) {
            VertexStore.Vertex vertex = vertexStore.getCursor(fromVertices.get(pairIndex));
            int p = 0;
            pointConsumer.consumePoint(p++, vertex.getFixedLat(), vertex.getFixedLon());
            int[] intermediates = geometries.get(pairIndex);
            int i = 0;
            while (i < intermediates.length) {
                pointConsumer.consumePoint(p++, intermediates[i++], intermediates[i++]);
            }
            vertex.seek(toVertices.get(pairIndex));
            pointConsumer.consumePoint(p, vertex.getFixedLat(), vertex.getFixedLon());
        }

        /** @return an envelope around the whole edge geometry, in fixed-point WGS84 degrees. */
        public Envelope getEnvelope () {
            Envelope envelope = new Envelope();
            forEachPoint((p, fixedLat, fixedLon) -> envelope.expandToInclude(fixedLon, fixedLat));
            return envelope;
        }

        /**
         * @return the number of segments in the geometry of the current edge.
         */
        public int nSegments () {
            int[] geom = geometries.get(pairIndex);
            if (geom != null) {
                // Number of packed lat-lon pairs plus the final segment.
                return (geom.length / 2) + 1;
            } else {
                // A single segment from the initial vertex to the final vertex.
                return 1;
            }
        }

        @Override
        public String toString() {
            String base = String.format("Edge from %d to %d. Length %f meters, speed %f kph.",
                        getFromVertex(), getToVertex(), getLengthMm() / 1000D, getSpeedKph());
            return base + getFlagsAsString();
        }

        public String getFlagsAsString () {
            StringBuilder sb = new StringBuilder();
            for (EdgeFlag flag : EdgeFlag.values()) {
                if (getFlag(flag)) {
                    sb.append(" ");
                    sb.append(flag.toString());
                }
            }
            return sb.toString();
        }

        /**
         * Creates text label from permissions
         *
         * It looks like walk,bike,car or none.
         * If any mode of transport is not allowed it is missing in return value.
         * @return
         */
        public String getPermissionsAsString() {
            StringJoiner sb = new StringJoiner(",");
            sb.setEmptyValue("none");
            if (getFlag(EdgeFlag.ALLOWS_PEDESTRIAN)) {
                sb.add("walk");
            }
            if (getFlag(EdgeFlag.ALLOWS_BIKE)) {
                sb.add("bike");
            }
            if (getFlag(EdgeFlag.ALLOWS_CAR)) {
                sb.add("car");
            }
            return sb.toString();
        }

        /**
         * Returns only enumSet of permission flags (CAR, BICYCLE, WALKING)
         * @return
         */
        public EnumSet<EdgeFlag> getPermissionFlags() {
            EnumSet<EdgeFlag> edgePermissionFlags = EnumSet.noneOf(EdgeFlag.class);
            for (EdgeFlag permissionFlag: PERMISSION_FLAGS) {
                if (getFlag(permissionFlag)) {
                    edgePermissionFlags.add(permissionFlag);
                }
            }
            return edgePermissionFlags;
        }

        public int getEdgeIndex() {
            return edgeIndex;
        }

        /**
         * @return whether this edge may be modified. It may not be modified if it is part of a baseline graph that has
         * been extended by a scenario. It may be modified if it's part of a baseline graph in the process of being
         * built, or if it was created as part of the scenario being applied.
         */
        public boolean isMutable() {
            return edgeIndex >= firstModifiableEdge;
        }

        /**
         * Set the flags for all on-street modes of transportation to "true", so that any mode can traverse this edge.
         */
        public void allowAllModes() {
            setFlag(EdgeFlag.ALLOWS_PEDESTRIAN);
            setFlag(EdgeFlag.ALLOWS_BIKE);
            setFlag(EdgeFlag.ALLOWS_CAR);
            setFlag(EdgeFlag.ALLOWS_WHEELCHAIR);
        }

        /**
         * Set the flags for all on-street modes of transportation to "false", so no modes can traverse this edge.
         */
        public void disallowAllModes() {
            clearFlag(EdgeFlag.ALLOWS_PEDESTRIAN);
            clearFlag(EdgeFlag.ALLOWS_BIKE);
            clearFlag(EdgeFlag.ALLOWS_CAR);
            clearFlag(EdgeFlag.ALLOWS_WHEELCHAIR);
        }

        public boolean allowsStreetMode(StreetMode mode) {
            if (mode == StreetMode.WALK) {
                return getFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            }
            if (mode == StreetMode.BICYCLE) {
                return getFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            }
            if (mode == StreetMode.CAR) {
                return getFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
            }
            throw new RuntimeException("Supplied mode not recognized.");
        }

        /** Set flags on the current edge allowing traversals by searches with any of the given streetModes enabled. */
        public void allowStreetModes (Iterable<StreetMode> streetModes) {
            for (StreetMode streetMode : streetModes) {
                allowStreetMode(streetMode);
            }
        }

        /** Set a flag on the current edge allowing traversals by searches with the given streetMode enabled. */
        public void allowStreetMode (StreetMode mode) {
            if (mode == StreetMode.WALK) {
                setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            } else if (mode == StreetMode.BICYCLE) {
                setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            } else if (mode == StreetMode.CAR) {
                setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
            } else {
                throw new RuntimeException("Mode not recognized:" + mode);
            }
        }

        public long getOSMID() {
            return osmids.get(pairIndex);
        }

        public void setWalkTimeFactor (double walkTimeFactor) {
            edgeTraversalTimes.setWalkTimeFactor(edgeIndex, walkTimeFactor);
        }

        public void setBikeTimeFactor (double bikeTimeFactor) {
            edgeTraversalTimes.setBikeTimeFactor(edgeIndex, bikeTimeFactor);
        }
    }

    /**
     * @return an Edge object that can be moved around in the EdgeStore to get a view of any edge in the graph.
     * This object is not pointing at any edge upon creation, so you'll need to call seek() on it to select an edge.
     */
    public Edge getCursor() {
        return new Edge();
    }

    /**
     * @return an Edge object that can be moved around in the EdgeStore to get a view of any edge in the graph.
     */
    public Edge getCursor(int pos) {
        Edge edge = new Edge();
        edge.seek(pos);
        return edge;
    }

    /** A functional interface that consumes segments in a street geometry one by one. */
    @FunctionalInterface
    public static interface SegmentConsumer {
        public void consumeSegment (int index, int fixedLat0, int fixedLon0, int fixedLat1, int fixedLon1);
    }

    /** A functional interface that consumes the points in a street geometry one by one. */
    @FunctionalInterface
    public static interface PointConsumer {
        public void consumePoint (int index, int fixedLat, int fixedLon);
    }

    public void dump () {
        Edge edge = getCursor();
        for (int e = 0; e < nEdges(); e++) {
            edge.seek(e);
            System.out.println(edge);
        }
    }

    /**
     * @return the total number of individual directed edges in the graph, including temporary ones from Scenarios.
     * Forward and backward edges in pairs are counted separately.
     */
    public int nEdges() {
        return flags.size();
    }

    /**
     * @return the number of forward+backward edge pairs in the graph, including temporary ones from applied Scenarios.
     * This should always be always nEdges/2.
     */
    public int nEdgePairs() {
        return fromVertices.size();
    }

    private EdgeStore() {
        // Private trivial constructor. Leaves all fields blank for use in extend-only copy method.
    }

    /**
     * Returns a semi-deep copy of this EdgeStore for use when applying Scenarios. Mutable objects and collections
     * will be cloned, but their contents will not. The lists containing the edge characteristics will be copied
     * in such a way that all threads applying Scenarios will share the same baseline data, and only extend those
     * lists.
     * We don't use clone() and make sure every field copy is explicit below, avoiding any unintentional shallow-copying
     * of collections or referenced data structures.
     *
     * @param copiedStreetLayer the copy of the street layer that this edgeStore will reference.
     */
    public EdgeStore extendOnlyCopy(StreetLayer copiedStreetLayer) {
        EdgeStore copy = new EdgeStore();
        copy.layer = copiedStreetLayer;
        copy.firstModifiableEdge = this.nEdges();
        // The Edge store references a vertex store, and the StreetLayer should also hold the same reference.
        // So the StreetLayer that makes this copy needs to grab a pointer to the new extend only VertexStore
        copy.vertexStore = vertexStore.extendOnlyCopy();
        copy.flags = new TIntAugmentedList(flags);
        // This is a deep copy, we should do an extend-copy but need a new class for that. Can we just use ints?
        copy.speeds = new TShortArrayList(speeds);
        // Vertex indices, geometries, and lengths are shared between pairs of forward and backward edges.
        copy.fromVertices = new TIntAugmentedList(fromVertices);
        copy.toVertices = new TIntAugmentedList(toVertices);
        copy.geometries = new AugmentedList<>(geometries);
        copy.lengths_mm = new TIntAugmentedList(lengths_mm);
        copy.osmids = new TLongAugmentedList(this.osmids);
        copy.temporarilyDeletedEdges = new TIntHashSet();
        //Angles are deep copy for now
        copy.inAngles = new TByteArrayList(inAngles);
        copy.outAngles = new TByteArrayList(outAngles);
        // We don't expect to add/change any turn restrictions.
        copy.turnRestrictions = turnRestrictions;
        copy.turnRestrictionsReverse = turnRestrictionsReverse;
        if (edgeTraversalTimes != null) {
            copy.edgeTraversalTimes = edgeTraversalTimes.extendOnlyCopy(copy);
        }
        return copy;
    }

    /**
     * If this EdgeStore has has a Scenario applied, it may contain temporary edges that are not in the baseline network.
     * The edges added temporarily by a Scenario should always be the numbers from firstModifiableEdge to nEdges.
     * Call the supplied function with the index number of every such temporarily added edge in this EdgeStore.
     */
    public void forEachTemporarilyAddedEdge (IntConsumer consumer) {
        if (this.isExtendOnlyCopy()) {
            for (int edge = firstModifiableEdge; edge < this.nEdges(); edge++) {
                consumer.accept(edge);
            }
        }
    }

    /**
     * If this EdgeStore has has a Scenario applied, some edges in the baseline network may be "removed" by hiding them.
     * Call the supplied function with the index number of every such temporarily deleted edge in this EdgeStore.
     */
    public void forEachTemporarilyDeletedEdge (IntConsumer consumer) {
        if (this.isExtendOnlyCopy()) {
            temporarilyDeletedEdges.forEach(edge -> {
                consumer.accept(edge);
                return true;
            });
        }
    }

    /**
     * Call the supplied function on the index number of every temporarily added edge in this EdgeStore,
     * then on every temporarily deleted edge. This is a convenience function, equivalent to calling
     * forEachTemporarilyAddedEdge followed by forEachTemporarilyDeletedEdge.
     */
    public void forEachTemporarilyAddedOrDeletedEdge (IntConsumer consumer) {
        forEachTemporarilyAddedEdge(consumer);
        forEachTemporarilyDeletedEdge(consumer);
    }


}
