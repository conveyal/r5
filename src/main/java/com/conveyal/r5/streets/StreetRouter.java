package com.conveyal.r5.streets;

import com.conveyal.r5.profile.Mode;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.util.TIntObjectHashMultimap;
import com.conveyal.r5.util.TIntObjectMultimap;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.Instant;
import java.util.Collection;
import java.util.Iterator;
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

    /**
     * It uses all nonzero limit as a limit whichever gets hit first
     * For example if distanceLimitMeters > 0 it is used as a limit. But if it isn't
     * timeLimitSeconds is used if it is bigger then 0. If both limits are 0 or both are set
     * warning is shown and both are used.
     */
    public int distanceLimitMeters = 0;
    public int timeLimitSeconds = 0;

    /**
     * Store the best state at the end of each edge. We store states at the ends of edges, rather than at vertices, so
     * that we can apply turn costs. You can't apply turn costs (which are vertex costs) when you are storing a single state
     * per vertex, because the vertex cost is not applied until leaving the vertex. This means that a state that must make
     * an expensive U-turn to reach the destination may beat out a state that is slightly less costly _at that vertex_ but
     * will complete the search with a cheap straight-through movement. We use the ends rather than the beginnings of edges
     * to avoid state proliferation (otherwise after traversing an edge you'd have to create states, many of which would
     * be dominated pretty quickly, for every outgoing edge at the to vertex).
     *
     * Storing states per edge is mathematically equivalent to creating a so-called edge-based graph in which all of the
     * street segments have been represented as nodes and all of the intersections/turn possibilities as edges, but that
     * is a very theoretical view and creates a semantic nightmare because it's hard to think about nodes that represent
     * things with dimension (not to mention never being sure whether you're talking about the original, standard graph
     * or the transformed, edge-based graph). We had a nightmarish time trying to keep this straight in OTP, and eventually
     * removed it. Using edge-based graphs to represent turn costs/restrictions is documented at http://geo.fsv.cvut.cz/gdata/2013/pin2/d/dokumentace/line_graph_teorie.pdf
     *
     * This would seem to obviate the need to have incomparable states at all, but it in fact does not, because of the existence
     * of complex turn restrictions that have via ways. This could be a simple U-turn on a dual carriageway, but could also be
     * something more complex (no right turn after left &c.). In these cases, we still have to have incomparable states when
     * we are partway through the restriction.
     *
     * When determining the weight at a vertex, one should just grab all the incoming edges, and take the minimum. However,
     * it's a bit more complicated to properly determine the time/weight at a split, because of turn costs. Suppose that
     * the best state at a particular vertex require a left turn onto the split edge; it is important to apply that left
     * turn costs. Even more important is to make sure that the split edge is not the end of a restricted turn; if it is,
     * one must reach the split via an alternate state.
     */
    // TODO we might be able to make this more efficient by taking advantage of the fact that we almost always have a
    // single state per edge (the only time we don't is when we're in the middle of a turn restriction).
    TIntObjectMultimap<State> bestStatesAtEdge = new TIntObjectHashMultimap<>();

    PriorityQueue<State> queue = new PriorityQueue<>((s0, s1) -> s0.weight - s1.weight);

    // If you set this to a non-negative number, the search will be directed toward that vertex .
    public int toVertex = ALL_VERTICES;

    /** Set individual properties here, or an entirely new request */
    public ProfileRequest profileRequest = new ProfileRequest();

    /** Search mode: we need a single mode, it is up to the caller to disentagle the modes set in the profile request */
    public Mode mode = Mode.WALK;

    private RoutingVisitor routingVisitor;

    private Split originSplit;

    private Split destinationSplit;

    private int bestWeightAtDestination = Integer.MAX_VALUE;

    /**
     * Here is previous streetRouter in multi router search
     * For example if we are searching for P+R we need 2 street searches
     * First from start to all car parks and next from all the car parks to transit stops
     * <p>
     * Second street router has first one in previous. This is needed so the paths can be reconstructed in response
     **/
    public StreetRouter previous;

    public void setRoutingVisitor(RoutingVisitor routingVisitor) {
        this.routingVisitor = routingVisitor;
    }

    /** Currently used for debugging snapping to vertices
     * TODO: API should probably be nicer
     * setOrigin on split or setOrigin that would return split
     * @return
     */
    public Split getOriginSplit() {
        return originSplit;
    }

    /**
     * @return a map from transit stop indexes to their distances from the origin.
     * Note that the TransitLayer contains all the information about which street vertices are transit stops.
     */
    public TIntIntMap getReachedStops() {
        TIntIntMap result = new TIntIntHashMap();

        streetLayer.linkedTransitLayer.stopForStreetVertex.forEachEntry((streetVertex, stop) -> {
            if (streetVertex == -1) return true;

            State state = getStateAtVertex(streetVertex);
            // TODO should this be time?
            if (state != null) result.put(stop, state.weight);
            return true; // continue iteration
        });

        return result;
    }

    /** Return a map where the keys are all the reached vertices, and the values are their distances from the origin. */
    public TIntIntMap getReachedVertices () {
        TIntIntMap result = new TIntIntHashMap();
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor();
        bestStatesAtEdge.forEachEntry((eidx, states) -> {
            if (eidx < 0) return true;

            State state = states.stream().reduce((s0, s1) -> s0.weight < s1.weight ? s0 : s1).get();
            e.seek(eidx);
            int vidx = e.getToVertex();

            if (!result.containsKey(vidx) || result.get(vidx) > state.weight)
                result.put(vidx, state.weight);

            return true; // continue iteration
        });
        return result;
    }

    /**
     * @return a map where all the keys are vertex indexes with the particular flag and all the values are states.
     */
    public TIntObjectMap<State> getReachedVertices (VertexStore.VertexFlag flag) {
        TIntObjectMap<State> result = new TIntObjectHashMap<>();
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor();
        VertexStore.Vertex v = streetLayer.vertexStore.getCursor();
        bestStatesAtEdge.forEachEntry((eidx, states) -> {
            if (eidx < 0) return true;

            State state = states.stream().reduce((s0, s1) -> s0.weight < s1.weight ? s0 : s1).get();
            e.seek(eidx);
            int vidx = e.getToVertex();
            v.seek(vidx);

            if (v.getFlag(flag)) {
                if (!result.containsKey(vidx) || result.get(vidx).weight > state.weight) {
                    result.put(vidx, state);
                }
            }

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
        TIntIntMap states = getReachedVertices();

        TIntList result = new TIntArrayList(states.size() * 2);
        // Convert stop vertex indexes in street layer to transit layer stop indexes.
        states.forEachEntry((vertexIndex, weight) -> {
            result.add(vertexIndex);
            result.add(weight);
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
     * @return true if edge was found near wanted coordinate
     */
    public boolean setOrigin (double lat, double lon) {
        Split split = streetLayer.findSplit(lat, lon, 300);
        if (split == null) {
            LOG.info("No street was found near the specified origin point of {}, {}.", lat, lon);
            return false;
        }
        originSplit = split;
        bestStatesAtEdge.clear();
        queue.clear();
        // from vertex is at end of back edge. Set edge correctly so that turn restrictions/costs are applied correctly
        // at the origin.
        State startState0 = new State(split.vertex0, split.edge + 1, profileRequest.getFromTimeDate(), mode);
        State startState1 = new State(split.vertex1, split.edge, profileRequest.getFromTimeDate(), mode);
        // TODO walk speed, assuming 1 m/sec currently.
        startState0.weight = split.distance0_mm / 1000;
        startState1.weight = split.distance1_mm / 1000;
        // NB not adding to bestStates, as it will be added when it comes out of the queue
        queue.add(startState0);
        queue.add(startState1);
        return true;
    }

    public void setOrigin (int fromVertex) {
        bestStatesAtEdge.clear();
        queue.clear();

        // NB backEdge of -1 is no problem as it is a special case that indicates that the origin was a vertex.
        State startState = new State(fromVertex, -1, profileRequest.getFromTimeDate(), mode);
        queue.add(startState);
    }

    /**
     * Adds multiple origins.
     *
     * Each bike Station is one origin. Weight is copied from state.
     * @param bikeStations map of bikeStation vertexIndexes and states Return of {@link #getReachedVertices(VertexStore.VertexFlag)}}
     * @param switchTime How many ms is added to state time (this is used when switching modes, renting bike, parking a car etc.)
     * @param switchCost This is added to the weight and is a cost of switching modes
     */
    public void setOrigin(TIntObjectMap<State> bikeStations, int switchTime, int switchCost) {
        bestStatesAtEdge.clear();
        queue.clear();

        bikeStations.forEachEntry((vertexIdx, bikeStationState) -> {
            // backEdge needs to be unique for each start state or they will wind up dominating each other.
            // subtract 1 from -vertexIdx because -0 == 0
            State state = new State(vertexIdx, -vertexIdx - 1, bikeStationState.getTime()+switchTime, mode);
            state.weight = bikeStationState.weight+switchCost;
            state.isBikeShare = true;
            queue.add(state);
            return true;
        });

    }

    public boolean setDestination (double lat, double lon) {
        this.destinationSplit = streetLayer.findSplit(lat, lon, 300);
        return this.destinationSplit != null;
    }

    public void setDestination (Split split) {
        this.destinationSplit = split;
    }

    /**
     * Call one of the setOrigin functions first.
     *
     * It uses all nonzero limit as a limit whichever gets hit first
     * For example if distanceLimitMeters > 0 it is used a limit. But if it isn't
     * timeLimitSeconds is used if it is bigger then 0. If both limits are 0 or both are set
     * warning is shown and both are used.
     */
    public void route () {

        final int distanceLimitMm;
        //This is needed otherwise timeLimitSeconds gets changed and
        // on next call of route on same streetRouter wrong warnings are returned
        // (since timeLimitSeconds is MAX_INTEGER not 0)
        final int tmpTimeLimitSeconds;

        if (distanceLimitMeters > 0) {
            //Distance in State is in mm wanted distance is in meters which means that conversion is necessary
            distanceLimitMm = distanceLimitMeters * 1000;
        } else {
            //We need to set distance limit to largest possible value otherwise nothing would get routed
            //since first edge distance would be larger then 0 m and routing would stop
            distanceLimitMm = Integer.MAX_VALUE;
        }
        if (timeLimitSeconds > 0) {
            tmpTimeLimitSeconds = timeLimitSeconds;
        } else {
            //Same issue with time limit
            tmpTimeLimitSeconds = Integer.MAX_VALUE;
        }

        if (timeLimitSeconds > 0 && distanceLimitMeters > 0) {
            LOG.warn("Both distance limit of {}m and time limit of {}s are set in streetrouter", distanceLimitMeters, timeLimitSeconds);
        } else if (timeLimitSeconds == 0 && distanceLimitMeters == 0) {
            LOG.warn("Distance and time limit are set to 0 in streetrouter. This means NO LIMIT in searching so WHOLE of street graph will be searched. This can be slow.");
        } else if (distanceLimitMeters > 0) {
            LOG.debug("Using distance limit of {}m", distanceLimitMeters);
        } else if (timeLimitSeconds > 0) {
            LOG.debug("Using time limit of {}s", timeLimitSeconds);
        }

        if (queue.size() == 0) {
            LOG.warn("Routing without first setting an origin, no search will happen.");
        }

        PrintStream printStream = null; // for debug output
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

        // TODO don't hardwire drive-on-right
        TurnCostCalculator turnCostCalculator = new TurnCostCalculator(streetLayer, true);
        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();

        QUEUE: while (!queue.isEmpty()) {
            State s0 = queue.poll();

            if (DEBUG_OUTPUT) {
                VertexStore.Vertex v = streetLayer.vertexStore.getCursor(s0.vertex);

                double lat = v.getLat();
                double lon = v.getLon();

                if (s0.backEdge != -1) {
                    EdgeStore.Edge e = streetLayer.edgeStore.getCursor(s0.backEdge);
                    v.seek(e.getFromVertex());
                    lat = (lat + v.getLat()) / 2;
                    lon = (lon + v.getLon()) / 2;
                }

                printStream.println(String.format("%.6f,%.6f,%d", v.getLat(), v.getLon(), s0.weight));
            }
            if (bestStatesAtEdge.containsKey(s0.backEdge)) {
                for (State state : bestStatesAtEdge.get(s0.backEdge)) {
                    // states in turn restrictions don't dominate anything
                    if (!state.inTurnRestriction)
                        continue QUEUE;
                }
            }

            if (toVertex > 0 && toVertex == s0.vertex) {
                // found destination
                break;
            }

            if (destinationSplit != null && (s0.vertex == destinationSplit.vertex0 || s0.vertex == destinationSplit.vertex1)) {
                // TODO make sure this state can actually reach the destination, applying turn costs and turn restrictions
                if (bestWeightAtDestination > s0.weight) bestWeightAtDestination = s0.weight;
            }

            if (s0.weight > bestWeightAtDestination) break;

            // non-dominated state coming off the pqueue is by definition the best way to get to that vertex
            // but states in turn restrictions don't dominate anything, to avoid resource limiting issues
            if (s0.inTurnRestriction)
                bestStatesAtEdge.put(s0.backEdge, s0);
            else {
                // we might need to save an existing state that is in a turn restriction so is codominant
                for (Iterator<State> it = bestStatesAtEdge.get(s0.backEdge).iterator(); it.hasNext();) {
                    State other = it.next();
                    if (s0.weight < other.weight) it.remove();
                    // avoid a ton of codominant states when there is e.g. duplicated OSM data
                    // However, save this state if it is not in a turn restriction and the other state is
                    else if (s0.weight == other.weight && !(other.inTurnRestriction && !s0.inTurnRestriction))
                        continue QUEUE;
                }
                bestStatesAtEdge.put(s0.backEdge, s0);
            }

            if (routingVisitor != null) {
                routingVisitor.visitVertex(s0);
            }

            // explore edges leaving this vertex
            streetLayer.outgoingEdges.get(s0.vertex).forEach(eidx -> {
                edge.seek(eidx);

                State s1 = edge.traverse(s0, mode, profileRequest, turnCostCalculator);

                if (s1 != null && s1.distance <= distanceLimitMm && s1.getDurationSeconds() < tmpTimeLimitSeconds) {
                    queue.add(s1);
                }

                return true; // iteration over edges should continue
            });
        }
        if (DEBUG_OUTPUT) {
            printStream.close();
        }
    }

    /** get a single best state at the end of an edge. There can be more than one state at the end of an edge due to turn restrictions */
    public State getStateAtEdge (int edgeIndex) {
        Collection<State> states = bestStatesAtEdge.get(edgeIndex);
        if (states.isEmpty()) {
            return null; // Unreachable
        }

        // get the lowest weight, even if it's in the middle of a turn restriction
        return states.stream().reduce((s0, s1) -> s0.weight < s1.weight ? s0 : s1).get();
    }

    /**
     * Get a single best state at a vertex. NB this should not be used for propagating to samples, as you need to apply
     * turn costs/restrictions during propagation.
     */
    public State getStateAtVertex (int vertexIndex) {
        State ret = null;

        for (TIntIterator it = streetLayer.incomingEdges.get(vertexIndex).iterator(); it.hasNext();) {
            int eidx = it.next();

            State state = getStateAtEdge(eidx);

            if (state == null) continue;

            if (ret == null) ret = state;
            else if (ret.weight > state.weight) ret = state;
        }

        return ret;
    }

    public int getTravelTimeToVertex (int vertexIndex) {
        State state = getStateAtVertex(vertexIndex);
        return state != null ? state.weight : Integer.MAX_VALUE;
    }

    /**
     * Returns state with smaller weight to vertex0 or vertex1
     *
     * If state to only one vertex exists return that vertex.
     * If state to none of the vertices exists returns null
     * @param split
     * @return
     */
    public State getState(Split split) {
        // TODO check turn restrictions and apply turn costs here!
        State weight0 = getStateAtVertex(split.vertex0);
        State weight1 = getStateAtVertex(split.vertex1);
        if (weight0 == null) {
            if (weight1 == null) {
                //Both vertices aren't found
                return null;
            } else {
                //vertex1 found vertex 0 not
                return weight1;
            }
        } else {
            //vertex 0 found vertex 1 not
            if (weight1 == null) {
                return weight0;
            } else {
                //both found
                if (weight0.weight < weight1.weight) {
                    return  weight0;
                } else {
                    return weight1;
                }
            }
        }
    }

    public Split getDestinationSplit() {
        return destinationSplit;
    }

    public static class State implements Cloneable {
        public int vertex;
        public int weight;
        public int backEdge;
        // the current time at this state, in milliseconds UNIX time
        protected int durationSeconds;
        //Distance in mm
        public int distance;
        public Mode mode;
        public State backState; // previous state in the path chain
        public boolean isBikeShare = false; //is true if vertex in this state is Bike sharing station where mode switching occurs

        /** we are in the middle of a turn restriction. */
        public boolean inTurnRestriction = false;

        public State(int atVertex, int viaEdge, long fromTimeDate, State backState) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            this.backState = backState;
            this.distance = backState.distance;
            this.durationSeconds = backState.durationSeconds;
        }

        public State(int atVertex, int viaEdge, long fromTimeDate, Mode mode) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            this.backState = null;
            this.distance = 0;
            this.mode = mode;
            this.durationSeconds = 0;
        }


        public void incrementTimeInSeconds(long seconds) {
            if (seconds < 0) {
                LOG.warn("A state's time is being incremented by a negative amount while traversing edge "
                    );
                //defectiveTraversal = true;
                return;
            }
            durationSeconds+=seconds;
        }

        public int getDurationSeconds() {
            return durationSeconds;
        }

        public long getTime() {
            return durationSeconds;
        }

        public void incrementWeight(float weight) {
            this.weight+=(int)weight;
        }

        public String dump() {
            State state = this;
            StringBuilder out = new StringBuilder();
            out.append("BEGIN PATH DUMP\n");
            while (state != null) {
                out.append(String.format("%s at %s via %s\n", state.vertex, state.weight, state.backEdge));
                state = state.backState;
            }
            out.append("END PATH DUMP\n");

            return out.toString();
        }
    }

}
