package com.conveyal.r5.streets;

import com.conveyal.osmlib.Node;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.trove.AugmentedList;
import com.conveyal.r5.trove.TIntAugmentedList;
import com.conveyal.r5.trove.TLongAugmentedList;
import com.conveyal.r5.util.TIntIntHashMultimap;
import com.conveyal.r5.util.TIntIntMultimap;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.LineString;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.TLongList;
import gnu.trove.list.TShortList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.list.array.TShortArrayList;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import gnu.trove.map.hash.TIntIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * A column store with parallel arrays is better than a struct simulation because:
 * 1. it is less fancy, 2. it is auto-resizing (not fixed size), 3. I've tried both and they're the same speed.
 *
 * Edges come in pairs that have the same origin and destination vertices and the same geometries, but reversed.
 * Therefore many of the arrays are only half as big as the number of edges. All even numbered edges are forward, all
 * odd numbered edges are reversed.
 *
 * Typically, somewhat more than half of street segment edges have intermediate points (ie. their geometry contains
 * points other than the two endpoints endpoints). Therefore it's more efficient to add a complete dense column for
 * references to intermediate point arrays, instead of using a sparse HashMap to store values only for those edges that
 * have intermediate points.
 *
 * For geometry storage I tried several methods. Full Netherlands load in 4GB of heap:
 * Build time is around 8 minutes. 2.5-3GB was actually in use.
 * List of int arrays: 246MB serialized, 5.2 sec write, 6.3 sec read.
 * List of int arrays, full lists (not only intermediates): 261MB, 6.1 sec write, 6.2 sec read.
 * Indexes into single contiguous int array: 259MB, 5 sec write, 7.5 sec read.
 * Currently I am using the first option as it is both readable and efficient.
 */
public class EdgeStore implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(EdgeStore.class);
    private static final short DEFAULT_SPEED_KPH = 50;
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    // The vertices that are referred to in these edges
    public VertexStore vertexStore;

    /** Boolean flags for every edge. Separate entries for forward and backward edges. */
    public TIntList flags;

    /**
     * One speed for each edge. Separate entries for forward and backward edges.
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

    /** Geometries. One entry for each edge pair */
    public List<int[]> geometries; // intermediate points along the edge, other than the intersection endpoints

    /**
     * When applying scenarios, we don't duplicate the entire set of edges and vertices. We extend them, treating
     * the baseline graph as immutable. We have to be careful not to change or delete any elements of that baseline
     * graph which is shared between all threads. All edges at or above the index firstModifiableEdge can be modified,
     * all lower indexes should be treated as immutable.
     * All edges from firstModifiableEdge up to nEdges() are temporary and are not in the spatial index.
     * This field also serves as an indication that a scenario is being applied and this EdgeStore is a protective copy,
     * whenever firstModifiableEdge > 0.
     */
    public int firstModifiableEdge = 0;

    /**
     * When applying a scenario, we can't touch the baseline graph which is shared between all threads. Whenever we
     * must delete one of these immutable edges (e.g. when splitting a road to connect a new stop) we instead record
     * its index in temporarilyDeletedEdges, meaning it should be ignored as if it did not exist in this thread.
     */
    public TIntSet temporarilyDeletedEdges = null;

    /**
     * If this EdgeStore has has a Scenario applied, this list contains all the edge indexes created by that scenario.
     * This is a bit redundant since the edges added temporarily by a Scenario should always be the numbers from
     * firstModifiableEdge to nEdges. But it's useful to have a list of these numbers around (e.g. for spatial index
     * queries) and placing it in a field rather than generating it on demand means the list instance gets reused.
     * TODO perhaps this should be NULL when this is not an extend-only copy of an EdgeStore.
     */
    public TIntList temporarilyAddedEdges = new TIntArrayList();

    /**
     * There's one case where this method will fail: using Scenarios to create street networks from a blank slate,
     * where there are no edges before the scenario is applied.
     *
     * @return true if this EdgeStore has already been extended,
     * and can therefore be modified without affecting the baseline graph shared between all threads.
     */
    public boolean isExtendOnlyCopy() {
        return firstModifiableEdge > 0;
    }

    /** Turn restrictions for turning _out of_ each edge */
    public TIntIntMultimap turnRestrictions;

    public StreetLayer layer;

    public static final transient EnumSet<EdgeFlag> PERMISSION_FLAGS = EnumSet
        .of(EdgeFlag.ALLOWS_PEDESTRIAN, EdgeFlag.ALLOWS_BIKE, EdgeFlag.ALLOWS_CAR);

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
        turnRestrictions = new TIntIntHashMultimap();
    }

    /**
     * Remove the specified edges from this edge store.
     * Removing edges causes their indexes to change, but the only place these indexes are used is in the incoming
     * and outgoing edge lists of vertices. Those edge lists are transient data structures derived from the edges
     * themselves, so fortunately we don't need to update them while the edges are shifted around, we just rebuild them
     * afterward.
     */
    public void remove (int[] edgesToRemove) {
        // Sort the list and traverse it backward. Removing an element only affects the array indices of elements later
        // in the list, so backward traversal ensures that all edge indexes remain valid during a bulk remove operation.
        Arrays.sort(edgesToRemove);
        int prevPair = -1;
        for (int cursor = edgesToRemove.length - 1; cursor >= 0; cursor--) {
            int edgePair = edgesToRemove[cursor] / 2;
            if (edgePair == prevPair) {
                // Ignore duplicate edge indexes, which would cause two different edges to be removed.
                continue;
            }
            prevPair = edgePair;
            // Flags and speeds arrays have separate elements for the forward and backward edges within a pair.
            // Use TIntList function to remove these two elements at once.
            // Note that the 1-arg remove function will remove a certain value from the list, not an element at an index.
            flags.remove(edgePair * 2, 2);
            speeds.remove(edgePair * 2, 2);
            // All other arrays have a single element describing both the forward and backward edges in an edge pair.
            fromVertices.remove(edgePair, 1);
            toVertices.remove(edgePair, 1);
            lengths_mm.remove(edgePair, 1);
            osmids.remove(edgePair, 1);
            // This is not a TIntList, so use the 1-arg remove function to remove by index.
            geometries.remove(edgePair);
        }
    }

    /**
     * Edge flags are various boolean values (requiring a single bit of information) that can be attached to each
     * edge in the street graph. They are each assigned a bit number from 0...31 so they can all be packed into a
     * single integer field for space and speed reasons.
     */
    public enum EdgeFlag {

        // Street categories.
        // FIXME some street categories are mutually exclusive and should not be flags, just narrow numbers.
        // Maybe reserve the first 4-5 bits (or a whole byte, and 16 bits for flags) for mutually exclusive edge types.
        UNUSED (0),
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
        /** Link edge, two should not be traversed one-after-another */
        LINK (14),

        // Permissions
        ALLOWS_PEDESTRIAN (15),
        ALLOWS_BIKE (16),
        ALLOWS_CAR (17),
        ALLOWS_WHEELCHAIR (18),
        //Set when OSM tags are wheelchair==limited currently unroutable
        LIMITED_WHEELCHAIR(19),

        // Bicycle level of traffic stress for this street.
        // See http://transweb.sjsu.edu/PDFs/research/1005-low-stress-bicycling-network-connectivity.pdf
        // Comments below pasted from that document.
        // FIXME bicycle LTS should not really be flags, the categories are mutually exclusive and can be stored in 2 bits.

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

        /** In each enum value this field should contain an integer with only a single bit switched on. */
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

        // Speed and flags are stored separately for each edge in a pair (unlike length, geom, etc.)

        // Forward edge.
        // No speed or flags are set, they must be set afterward using the edge cursor.
        speeds.add(DEFAULT_SPEED_KPH);
        flags.add(0);

        // Backward edge.
        // No speed or flags are set, they must be set afterward using the edge cursor.
        speeds.add(DEFAULT_SPEED_KPH);
        flags.add(0);

        if (isExtendOnlyCopy()) {
            // If we're working on a copy made for a Scenario the edges will not be spatially indexed, so record them.
            temporarilyAddedEdges.add(forwardEdgeIndex);
            temporarilyAddedEdges.add(forwardEdgeIndex + 1);
        }

        return getCursor(forwardEdgeIndex);
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

        /** move the cursor back one edge */
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

        public float getSpeedMs() {
            return (float) ((speeds.get(edgeIndex) / 100.));
        }

        public float getSpeedkmh() {
            return (float) ((speeds.get(edgeIndex) / 100.) * 3.6);
        }

        public void setSpeed(short speed) {
            speeds.set(edgeIndex, speed);
        }

        public int getLengthMm () {
            return lengths_mm.get(pairIndex);
        }

        /**
         * Copy the flags and speeds from the supplied Edge cursor into this one.
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
            osmids.set(pairIndex, other.pairIndex);
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
                return Float.NaN;
            } else if (traverseStreetMode == StreetMode.CAR) {
                /*if (options.useTraffic) {
                    //TODO: speed based on traffic information
                }*/
                return getSpeedMs();
            }
            return options.getSpeed(traverseStreetMode);
        }

        public StreetRouter.State traverse (StreetRouter.State s0, StreetMode streetMode, ProfileRequest req, TurnCostCalculator turnCostCalculator) {
            StreetRouter.State s1 = new StreetRouter.State(getToVertex(), edgeIndex, s0);
            float speedms = calculateSpeed(req, streetMode);
            float time = (float) (getLengthM() / speedms);
            float weight = 0;

            if (!canTurnFrom(s0, s1)) return null;

            // clear out turn restrictions if they're empty
            if (s1.turnRestrictions != null && s1.turnRestrictions.isEmpty()) s1.turnRestrictions = null;

            // figure out which turn res

            // add turn restrictions that start on this edge
            // Turn restrictions only apply to cars for now. This is also coded in canTurnFrom, so change it both places
            // if/when it gets changed.
            if (s0.streetMode == StreetMode.CAR && turnRestrictions.containsKey(getEdgeIndex())) {
                if (s1.turnRestrictions == null) s1.turnRestrictions = new TIntIntHashMap();
                turnRestrictions.get(getEdgeIndex()).forEach(r -> {
                    s1.turnRestrictions.put(r, 1); // we have traversed one edge
                    return true; // continue iteration
                });
            }

            if (s0.backEdge != -1 && getFlag(EdgeFlag.LINK) && getCursor(s0.backEdge).getFlag(EdgeFlag.LINK))
                // two link edges in a row, in other words a shortcut. Disallow this.
                return null;

            //Currently weigh is basically the same as weight. It differs only on stairs and when walking.

            s1.streetMode = streetMode;
            if (streetMode == StreetMode.WALK && getFlag(EdgeFlag.ALLOWS_PEDESTRIAN)) {
                weight = time;
                //If wheelchair path is requested and this edge doesn't allow wheelchairs we need to find another edge
                if (req.wheelchair && !getFlag(EdgeFlag.ALLOWS_WHEELCHAIR)) {
                    return null;
                }
                //elevation which changes weight
            } else if (streetMode == StreetMode.BICYCLE) {
                // walk a bike if biking is not allowed on this edge, or if the traffic stress is too high
                boolean walking = !getFlag(EdgeFlag.ALLOWS_BIKE);

                if (req.bikeTrafficStress > 0 && req.bikeTrafficStress < 4) {
                    if (getFlag(EdgeFlag.BIKE_LTS_4)) walking = true;
                    if (req.bikeTrafficStress < 3 && getFlag(EdgeFlag.BIKE_LTS_3)) walking = true;
                    if (req.bikeTrafficStress < 2 && getFlag(EdgeFlag.BIKE_LTS_2)) walking = true;
                }

                //elevation costs
                //Triangle (bikesafety, flat, quick)
                weight = time;
                // TODO bike walking costs when switching bikes

                // only walk if you're allowed to
                if (walking && !getFlag(EdgeFlag.ALLOWS_PEDESTRIAN)) return null;


                if (walking) {
                    //TODO: set bike walking in state
                    s1.streetMode = StreetMode.WALK;
                    // * 1.5 to account for time to get off bike and slower walk speed once off
                    // this will tend to prefer to bike a slightly longer route than walk a long way,
                    // but will allow walking to cross a busy street, etc.
                    weight *=1.5;
                }

            } else if (streetMode == StreetMode.CAR && getFlag(EdgeFlag.ALLOWS_CAR)) {
                weight = time;
            } else
                return null; // this mode cannot traverse this edge


            if(getFlag(EdgeFlag.STAIRS)) {
                weight*=3.0; //stair reluctance
            } else if (streetMode == StreetMode.WALK) {
                weight*=2.0; //walk reluctance
            }



            int roundedTime = (int) Math.ceil(time);

            // negative backedge is start of search.
            int turnCost = s0.backEdge >= 0 ? turnCostCalculator.computeTurnCost(s0.backEdge, getEdgeIndex(),
                streetMode) : 0;

            s1.incrementTimeInSeconds(roundedTime + turnCost);
            s1.incrementWeight(weight + turnCost);
            s1.distance += getLengthMm();

            // make sure we don't have states that don't increment weight/time, otherwise we can get weird loops
            if (s1.weight == s0.weight) s1.weight += 1;
            if (s1.durationSeconds == s0.durationSeconds) s1.durationSeconds += 1;
            if (s1.distance == s0.distance) s1.distance += 1;

            return s1;
        }

        /** Can we turn onto this edge from this state? Also copies still-applicable restrictions forward. */
        public boolean canTurnFrom (StreetRouter.State s0, StreetRouter.State s1) {
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

                    if (posInRestriction < restriction.viaEdges.length) {
                        if (getEdgeIndex() != restriction.viaEdges[posInRestriction]) {
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
                        if (restriction.toEdge != getEdgeIndex()) {
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

        public void setGeometry (List<Node> nodes) {
            // The same empty int array represents all straight-line edges.
            if (nodes.size() <= 2) {
                geometries.set(pairIndex, EMPTY_INT_ARRAY);
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

            // NB it initially appears that we are reversing the from and to vertex twice, but keep in mind that getFromVertex
            // returns the from vertex when on a forward edge, or the to vertex on a back edge.
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

        /** @return an envelope around the whole edge geometry. */
        public Envelope getEnvelope() {
            Envelope envelope = new Envelope();
            forEachPoint((p, fixedLat, fixedLon) -> {
                envelope.expandToInclude(fixedLon, fixedLat);
            });
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
                        getFromVertex(), getToVertex(), getLengthMm() / 1000D, getSpeedkmh());
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

        public long getOSMID() {
            return osmids.get(pairIndex);
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
     */
    public EdgeStore extendOnlyCopy() {
        EdgeStore copy = new EdgeStore();
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
        copy.temporarilyAddedEdges = new TIntArrayList();
        // We don't expect to add/change any turn restrictions.
        copy.turnRestrictions = turnRestrictions;
        return copy;
    }

}
