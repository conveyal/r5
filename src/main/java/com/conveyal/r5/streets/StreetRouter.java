package com.conveyal.r5.streets;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.PriorityQueue;

/**
 * This routes over the street layer of a TransitNetwork.
 * It is a throw-away calculator object that retains routing state and after the search is finished.
 * Additional functions are called to retrieve the routing results from that state.
 */
public class StreetRouter {

    private static final Logger LOG = LoggerFactory.getLogger(StreetRouter.class);

    private static final boolean DEBUG_OUTPUT = false;

    public static final int ALL_VERTICES = -1;

    public final StreetLayer streetLayer;

    public int distanceLimitMeters = 2_000;

    TIntObjectMap<State> bestStates = new TIntObjectHashMap<>();

    PriorityQueue<State> queue = new PriorityQueue<>((s0, s1) -> s0.weight - s1.weight);

    // If you set this to a non-negative number, the search will be directed toward that vertex .
    public int toVertex = ALL_VERTICES;

    /**
     * @return a map from transit stop indexes to their distances from the origin.
     * Note that the TransitLayer contains all the information about which street vertices are transit stops.
     */
    public TIntIntMap getReachedStops() {
        TIntIntMap result = new TIntIntHashMap();
        // Convert stop vertex indexes in street layer to transit layer stop indexes.
        bestStates.forEachEntry((vertexIndex, state) -> {
            int stopIndex = streetLayer.linkedTransitLayer.stopForStreetVertex.get(vertexIndex);
            // -1 indicates no value, this street vertex is not a transit stop
            if (stopIndex >= 0) {
                result.put(stopIndex, state.weight);
            }
            return true; // continue iteration
        });
        return result;
    }

    /** Return a map of all the reached vertices to their distances from the origin */
    public TIntIntMap getReachedVertices () {
        TIntIntMap result = new TIntIntHashMap();
        bestStates.forEachEntry((vidx, state) -> {
            result.put(vidx, state.weight);
            return true; // continue iteration
        });
        return result;
    }

    /**
     * Get a distance table to all street vertices touched by the last search operation on this StreetRouter.
     * @return A packed list of (vertex, distance) for every reachable street vertex.
     * This is currently returning the weight, which is the distance in meters.
     */
    public int[] getStopTree () {
        TIntList result = new TIntArrayList(bestStates.size() * 2);
        // Convert stop vertex indexes in street layer to transit layer stop indexes.
        bestStates.forEachEntry((vertexIndex, state) -> {
            result.add(vertexIndex);
            result.add(state.weight);
            return true; // continue iteration
        });
        return result.toArray();
    }

    public StreetRouter (StreetLayer streetLayer) {
        this.streetLayer = streetLayer;
    }

    /**
     * @param lat Latitude in floating point (not fixed int) degrees.
     * @param lon Longitude in flating point (not fixed int) degrees.
     */
    public void setOrigin (double lat, double lon) {
        Split split = streetLayer.findSplit(lat, lon, 300);
        if (split == null) {
            LOG.info("No street was found near the specified origin point of {}, {}.", lat, lon);
            return;
        }
        bestStates.clear();
        queue.clear();
        State startState0 = new State(split.vertex0, -1, null);
        State startState1 = new State(split.vertex1, -1, null);
        // TODO walk speed, assuming 1 m/sec currently.
        startState0.weight = split.distance0_mm / 1000;
        startState1.weight = split.distance1_mm / 1000;
        // NB not adding to bestStates, as it will be added when it comes out of the queue
        queue.add(startState0);
        queue.add(startState1);
    }

    public void setOrigin (int fromVertex) {
        bestStates.clear();
        queue.clear();
        State startState = new State(fromVertex, -1, null);
        queue.add(startState);
    }

    /**
     * Call one of the setOrigin functions first.
     */
    public void route () {

        if (queue.size() == 0) {
            LOG.warn("Routing without first setting an origin, no search will happen.");
        }

        PrintStream printStream; // for debug output
        if (DEBUG_OUTPUT) {
            File debugFile = new File(String.format("street-router-debug.csv"));
            OutputStream outputStream;
            try {
                outputStream = new BufferedOutputStream(new FileOutputStream(debugFile));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            printStream = new PrintStream(outputStream);
            printStream.println("lat,lon,weight");
        }

        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();
        while (!queue.isEmpty()) {
            State s0 = queue.poll();

            if (DEBUG_OUTPUT) {
                VertexStore.Vertex v = streetLayer.vertexStore.getCursor(s0.vertex);
                printStream.println(String.format("%.6f,%.6f,%d", v.getLat(), v.getLon(), s0.weight));
            }
            if (bestStates.containsKey(s0.vertex))
                // dominated
                continue;

            if (toVertex > 0 && toVertex == s0.vertex) {
                // found destination
                break;
            }

            // non-dominated state coming off the pqueue is by definition the best way to get to that vertex
            bestStates.put(s0.vertex, s0);

            // explore edges leaving this vertex
            streetLayer.outgoingEdges.get(s0.vertex).forEach(eidx -> {
                edge.seek(eidx);

                State s1 = edge.traverse(s0);

                if (s1 != null && s1.weight <= distanceLimitMeters) {
                    queue.add(s1);
                }

                return true; // iteration over edges should continue
            });
        }
        if (DEBUG_OUTPUT) {
            printStream.close();
        }
    }
    public int getTravelTimeToVertex (int vertexIndex) {
        State state = bestStates.get(vertexIndex);
        if (state == null) {
            return Integer.MAX_VALUE; // Unreachable
        }
        return state.weight; // TODO true walk speed
    }

    public static class State implements Cloneable {
        public int vertex;
        public int weight;
        public int backEdge;
        public State backState; // previous state in the path chain
        public State nextState; // next state at the same location (for turn restrictions and other cases with co-dominant states)
        public State (int atVertex, int viaEdge, State backState) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            this.backState = backState;
        }
    }

}
