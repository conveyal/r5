package com.conveyal.r5.streets;

import com.conveyal.osmlib.Node;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.Mode;
import com.conveyal.r5.profile.ProfileRequest;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.LineString;
import gnu.trove.list.TIntList;
import gnu.trove.list.TShortList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.list.array.TShortArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;

/**
 * Column store is better than struct simulation because 1. it is less fancy, 2. it is auto-resizing (not fixed size),
 * 3. I've tried both and they're the same speed.
 *
 * Edges come in pairs that have the same origin and destination vertices and the same geometries, but reversed.
 * Therefore many of the arrays are only half as big as the number of edges. All even numbered edges are forward, all
 * odd numbered edges are reversed.
 *
 * Typically, somewhat more than half of street segment edges have intermediate points (other than the two intersection
 * endpoints). Therefore it's more efficient to add a complete dense column for the intermediate point arrays, instead
 * of using a sparse hashmap to store values only for edges with intermediate points.
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
    private static final int DEFAULT_SPEED_KPH = 50;
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    // The vertices that are referred to in these edges
    private VertexStore vertexStore;

    int nEdges = 0;

    /** Flags for this edge.  One entry for each forward and each backward edge. */
    protected List<EnumSet<EdgeFlag>> flags;

    /** Speed for this edge.
     * One entry for each forward and each backward edge.
     * rounded (m/s * 100) (2 decimal places)
     * Saved speed is mostly the same as speed saved as m/s * 1000 it differs in second decimal place and is 0.024% smaller
     *
     * This way of saving speeds is 3.5% smaller then previous (saving 7 decimal places)
     */
    protected TShortList speeds;

    /** From vertices. One entry for each edge pair */
    protected TIntList fromVertices;

    /** To vertices. One entry for each edge pair */
    protected TIntList toVertices;

    /** Length (millimeters). One entry for each edge pair */
    protected TIntList lengths_mm;

    /** Geometries. One entry for each edge pair */
    protected List<int[]> geometries; // intermediate points along the edge, other than the intersection endpoints

    public static final transient EnumSet<EdgeFlag> PERMISSION_FLAGS = EnumSet
        .of(EdgeFlag.ALLOWS_PEDESTRIAN, EdgeFlag.ALLOWS_BIKE, EdgeFlag.ALLOWS_CAR);

    public EdgeStore (VertexStore vertexStore, int initialSize) {
        this.vertexStore = vertexStore;
        // There is one flags and speeds entry per edge.
        flags = new ArrayList<>(initialSize);
        speeds = new TShortArrayList(initialSize);
        // Vertex indices, geometries, and lengths are shared between pairs of forward and backward edges.
        int initialEdgePairs = initialSize / 2;
        fromVertices = new TIntArrayList(initialEdgePairs);
        toVertices = new TIntArrayList(initialEdgePairs);
        geometries = new ArrayList<>(initialEdgePairs);
        lengths_mm = new TIntArrayList(initialEdgePairs);
    }

    /** Remove the specified edges from this edge store */
    public void remove (int[] edgesToOmit) {
        // be clever: sort the list descending, because removing an edge only affects the indices of
        // edges that appear later in the graph.
        Arrays.sort(edgesToOmit);

        int prevEdge = -1;
        for (int cursor = edgesToOmit.length - 1; cursor >= 0; cursor--) {
            int edge = edgesToOmit[cursor] / 2;

            if (edge == prevEdge)
                continue; // ignore duplicates

            prevEdge = edge;
            // flags and speeds have separate entries for forward and backwards edges
            flags.remove(edge * 2 + 1); // remove the back edge first, before we shift the array around
            flags.remove(edge * 2);
            // note using 2 int version because it is an offset not a value that we want to remove
            speeds.remove(edge * 2, 2);

            // everything else has a single entry for forward and backward edges
            fromVertices.remove(edge, 1);
            toVertices.remove(edge, 1);
            lengths_mm.remove(edge, 1);
            // this is not a TIntList
            geometries.remove(edge);
            nEdges -= 2;
        }
    }

    // Maybe reserve the first 4-5 bits (or a whole byte, and 16 bits for flags) for mutually exclusive edge types.
    // Maybe we should have trunk, secondary, tertiary, residential etc. as types 0...6
    // SIDEWALK(1),     CROSSING(2),     ROUNDABOUT(3),     ELEVATOR(4),     STAIRS(5),     PLATFORM(6),

    public enum EdgeFlag {
        UNUSED,
        BIKE_PATH,
        SIDEWALK,
        CROSSING,
        ROUNDABOUT,
        ELEVATOR,
        STAIRS,
        PLATFORM,
        BOGUS_NAME,
        NO_THRU_TRAFFIC,
        NO_THRU_TRAFFIC_PEDESTRIAN,
        NO_THRU_TRAFFIC_BIKE,
        NO_THRU_TRAFFIC_CAR,
        SLOPE_OVERRIDE,
        TRANSIT_LINK, // This edge is a one-way connection from a street to a transit stop. Target is a transit stop index, not an intersection index.

        // Permissions
        ALLOWS_PEDESTRIAN,
        ALLOWS_BIKE,
        ALLOWS_CAR,
        ALLOWS_WHEELCHAIR,

        // Bicycle level of traffic stress: http://transweb.sjsu.edu/PDFs/research/1005-low-stress-bicycling-network-connectivity.pdf
        // comments below pasted from document.

        /**
         * Presenting little traffic stress and demanding little attention from cyclists, and attractive enough for a
         * relaxing bike ride. Suitable for almost all cyclists, including children trained to safely cross intersections.
         * On links, cyclists are either physically separated from traffic, or are in an exclusive bicycling zone next to
         * a slow traffic stream with no more than one lane per direction, or are on a shared road where they interact
         * with only occasional motor vehicles (as opposed to a stream of traffic) with a low speed differential. Where
         * cyclists ride alongside a parking lane, they have ample operating space outside the zone into which car
         * doors are opened. Intersections are easy to approach and cross.
         */
        BIKE_LTS_1,

        /**
         * Presenting little traffic stress and therefore suitable to most adult cyclists but demanding more attention
         * than might be expected from children. On links, cyclists are either physically separated from traffic, or are
         * in an exclusive bicycling zone next to a well-confined traffic stream with adequate clearance from a parking
         * lane, or are on a shared road where they interact with only occasional motor vehicles (as opposed to a
         * stream of traffic) with a low speed differential. Where a bike lane lies between a through lane and a rightturn
         * lane, it is configured to give cyclists unambiguous priority where cars cross the bike lane and to keep
         * car speed in the right-turn lane comparable to bicycling speeds. Crossings are not difficult for most adults.
         */
        BIKE_LTS_2,

        /**
         * More traffic stress than LTS 2, yet markedly less than the stress of integrating with multilane traffic, and
         * therefore welcome to many people currently riding bikes in American cities. Offering cyclists either an
         * exclusive riding zone (lane) next to moderate-speed traffic or shared lanes on streets that are not multilane
         * and have moderately low speed. Crossings may be longer or across higher-speed roads than allowed by
         * LTS 2, but are still considered acceptably safe to most adult pedestrians.
         */
        BIKE_LTS_3,

        /**
         * A level of stress beyond LTS3. (this is in fact the official definition. -Ed.)
         */
        BIKE_LTS_4 // also known as FLORIDA_AVENUE
    }

    /**
     * This creates the bare topological edge pair with a length.
     * Flags, detailed geometry, etc. must be set using an edge cursor.
     * This avoids having a tangle of different edge creator functions for different circumstances.
     * @return a cursor pointing to the forward edge in the pair, which always has an even index.
     */
    public Edge addStreetPair(int beginVertexIndex, int endVertexIndex, int edgeLengthMillimeters,
        EnumSet<EdgeFlag> forwardFlags, EnumSet<EdgeFlag> backFlags, short forwardSpeed,
        short backwardSpeed) {

        // Store only one length, set of endpoints, and intermediate geometry per pair of edges.
        lengths_mm.add(edgeLengthMillimeters);
        fromVertices.add(beginVertexIndex);
        toVertices.add(endVertexIndex);
        geometries.add(EMPTY_INT_ARRAY);

        // Forward edge
        speeds.add(forwardSpeed);
        flags.add(forwardFlags);

        // avoid confusion later on
        if (backFlags == forwardFlags)
            backFlags = forwardFlags.clone();

        // Backward edge
        speeds.add(backwardSpeed);
        flags.add(backFlags);

        // Increment total number of edges created so far, and return the index of the first new edge.
        int forwardEdgeIndex = nEdges;
        nEdges += 2;
        return getCursor(forwardEdgeIndex);

    }

    /** Inner class that serves as a cursor: points to a single edge in this store, and can be moved to other indexes. */
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
            return edgeIndex < nEdges;
        }

        /** Jump to a specific edge number. */
        public void seek(int pos) {
            if (pos < 0)
                throw new ArrayIndexOutOfBoundsException("Attempt to seek to negative edge number");
            if (pos >= nEdges)
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
            return flags.get(edgeIndex).contains(flag);
        }

        public void setFlag(EdgeFlag flag) {
            flags.get(edgeIndex).add(flag);
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
        private float calculateSpeed(ProfileRequest options, Mode traverseMode,
            long time) {
            if (traverseMode == null) {
                return Float.NaN;
            } else if (traverseMode == Mode.CAR) {
                /*if (options.useTraffic) {
                    //TODO: speed based on traffic information
                }*/
                return getSpeedMs();
            }
            return options.getSpeed(traverseMode);
        }

        public StreetRouter.State traverse (StreetRouter.State s0, Mode mode, ProfileRequest req) {
            StreetRouter.State s1 = new StreetRouter.State(getToVertex(), edgeIndex,
                s0.getTime(), s0);
            s1.nextState = null;
            s1.weight = s0.weight;
            float speedms = calculateSpeed(req, mode, s0.getTime());
            float time = (float) (getLengthM() / speedms);
            float weight = 0;

            //Currently weigh is basically the same as weight. It differs only on stairs and when walking.



            if (mode == Mode.WALK && getFlag(EdgeFlag.ALLOWS_PEDESTRIAN)) {
                weight = time;
                //elevation which changes weight
            } else if (mode == Mode.BICYCLE && getFlag(EdgeFlag.ALLOWS_BIKE)) {
                if (req.bikeTrafficStress > 0 && req.bikeTrafficStress < 4) {
                    if (getFlag(EdgeFlag.BIKE_LTS_4)) return null;
                    if (req.bikeTrafficStress < 3 && getFlag(EdgeFlag.BIKE_LTS_3)) return null;
                    if (req.bikeTrafficStress < 2 && getFlag(EdgeFlag.BIKE_LTS_2)) return null;
                }

                //elevation costs
                //Triangle (bikesafety, flat, quick)
                weight = time;
                // TODO bike walking costs when switching bikes
            }
            else if (mode == Mode.CAR && getFlag(EdgeFlag.ALLOWS_CAR)) {
                weight = time;
            } else
                return null; // this mode cannot traverse this edge


            if(getFlag(EdgeFlag.STAIRS)) {
                weight*=3.0; //stair reluctance
            } else if (mode == Mode.WALK) {
                weight*=2.0; //walk reluctance
            }

            //TODO: turn costs


            int roundedTime = (int) Math.ceil(time);
            s1.incrementTimeInSeconds(roundedTime);
            s1.incrementWeight(weight);
            return s1;
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
         * Call a function on every segment in this edges's geometry.
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
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Edge from %d to %d. Length %f meters, speed %f kph.",
                    getFromVertex(), getToVertex(), getLengthMm() / 1000D, getSpeedkmh()));
            for (EdgeFlag flag : flags.get(edgeIndex)) {
                sb.append(flag.toString());
            }
            return sb.toString();
        }

        public EnumSet<EdgeFlag> getFlags() {
            return flags.get(edgeIndex);
        }

        public String getFlagsAsString() {
            return getFlags().toString();
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
    }

    public Edge getCursor() {
        return new Edge();
    }

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
        for (int e = 0; e < nEdges; e++) {
            edge.seek(e);
            System.out.println(edge);
        }
    }

    public int getnEdges() {
        return nEdges;
    }
}
