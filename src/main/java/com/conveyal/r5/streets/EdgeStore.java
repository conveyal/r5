package com.conveyal.r5.streets;

import com.conveyal.modes.StreetMode;
import com.conveyal.r5.rastercost.CostField;
import com.conveyal.r5.trove.AugmentedList;
import com.conveyal.r5.trove.TByteAugmentedList;
import com.conveyal.r5.trove.TIntAugmentedList;
import com.conveyal.r5.trove.TLongAugmentedList;
import com.conveyal.util.TIntIntHashMultimap;
import com.conveyal.util.TIntIntMultimap;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
    public static final int[] EMPTY_INT_ARRAY = new int[0];

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

    /** OSM ids of edges. One entry for each edge pair. Should we really be serializing this much data on every edge? */
    public TLongList osmids;

    /**
     * For each edge _pair_, the OSM highway class it was derived from. Integer codes are defined in the StreetClass
     * enum. Like OSM IDs and street names, these could be factored out into a table of OSM way data.
     */
    public TByteList streetClasses;

    /**
     * Geometries. One entry for each edge pair. These are packed lists of lat, lon, lat, lon... as fixed-point
     * integers, and don't include the endpoints (i.e. don't include the intersection vertices, only intermediate
     * points). The entry for edges with no intermediate points will be a canonical zero-length array, not null.
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
        // FIXME this definition will fail if we ever allow scenarios on top of completely empty street networks.
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

    /**
     * Holds zero or more sets of data, typically derived from rasters, which represent scalar or boolean fields through
     * which the streets pass. Traversal costs are evaluated over these fields, in the manner of a path integral.
     * If this is null or empty no such costs will be applied.
     */
    public List<CostField> costFields;

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
        streetClasses = new TByteArrayList(initialEdgePairs);
        inAngles = new TByteArrayList(initialEdgePairs);
        outAngles = new TByteArrayList(initialEdgePairs);
        turnRestrictions = new TIntIntHashMultimap();
        turnRestrictionsReverse = new TIntIntHashMultimap();
        edgeTraversalTimes = null;
        costFields = null;
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
        streetClasses.add(StreetClass.OTHER.code);
        inAngles.add((byte) 0);
        outAngles.add((byte) 0);

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

        Edge edge = new Edge(this, forwardEdgeIndex);
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
    void startTurnRestriction(StreetMode streetMode, boolean reverseSearch, RoutingState s1) {
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
        copy.streetClasses = new TByteAugmentedList(this.streetClasses);
        copy.temporarilyDeletedEdges = new TIntHashSet();
        copy.inAngles = new TByteAugmentedList(inAngles);
        copy.outAngles = new TByteAugmentedList(outAngles);
        // We don't expect to add/change any turn restrictions. TODO Consider split streets though.
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
