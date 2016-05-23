package com.conveyal.r5.streets;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransitLayer;
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
import java.util.*;

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

    // TODO don't hardwire drive-on-right
    private TurnCostCalculator turnCostCalculator;

    /**
     * It uses all nonzero limit as a limit whichever gets hit first
     * For example if distanceLimitMeters > 0 it is used as a limit. But if it isn't
     * timeLimitSeconds is used if it is bigger then 0. If both limits are 0 or both are set
     * warning is shown and both are used.
     */
    public int distanceLimitMeters = 0;
    public int timeLimitSeconds = 0;

    /** What routing variable (weight, distance, etc.) should be used in the dominance function? */
    public State.RoutingVariable dominanceVariable = State.RoutingVariable.WEIGHT;

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

    PriorityQueue<State> queue = new PriorityQueue<>((s0, s1) -> s0.getRoutingVariable(dominanceVariable) - s1.getRoutingVariable(dominanceVariable));

    // If you set this to a non-negative number, the search will be directed toward that vertex .
    public int toVertex = ALL_VERTICES;

    /** Set individual properties here, or an entirely new request */
    public ProfileRequest profileRequest = new ProfileRequest();

    /** Search mode: we need a single mode, it is up to the caller to disentagle the modes set in the profile request */
    public StreetMode streetMode = StreetMode.WALK;

    private RoutingVisitor routingVisitor;

    private Split originSplit;

    private Split destinationSplit;

    /** The best value of the chosen dominance variable at the destination, used to prune the search */
    private int bestValueAtDestination = Integer.MAX_VALUE;

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
        TransitLayer transitLayer = streetLayer.parentNetwork.transitLayer;
        transitLayer.stopForStreetVertex.forEachEntry((streetVertex, stop) -> {
            if (streetVertex == -1) return true;
            State state = getStateAtVertex(streetVertex);
            // TODO should this be time?
            if (state != null) result.put(stop, state.getRoutingVariable(dominanceVariable));
            return true; // continue iteration
        });
        return result;
    }

    /** Return a map where the keys are all the reached vertices, and the values are their distances from the origin (as used in the chosen dominance function). */
    public TIntIntMap getReachedVertices () {
        TIntIntMap result = new TIntIntHashMap();
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor();
        bestStatesAtEdge.forEachEntry((eidx, states) -> {
            if (eidx < 0) return true;

            State state = states.stream()
                    .reduce((s0, s1) -> s0.getRoutingVariable(dominanceVariable) < s1.getRoutingVariable(dominanceVariable) ? s0 : s1).get();
            e.seek(eidx);
            int vidx = e.getToVertex();

            if (!result.containsKey(vidx) || result.get(vidx) > state.getRoutingVariable(dominanceVariable))
                result.put(vidx, state.getRoutingVariable(dominanceVariable));

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

            State state = states.stream().reduce((s0, s1) -> s0.getRoutingVariable(dominanceVariable) < s1.getRoutingVariable(dominanceVariable) ? s0 : s1).get();
            e.seek(eidx);
            int vidx = e.getToVertex();
            v.seek(vidx);

            if (v.getFlag(flag)) {
                if (!result.containsKey(vidx) || result.get(vidx).getRoutingVariable(dominanceVariable) > state.getRoutingVariable(dominanceVariable)) {
                    result.put(vidx, state);
                }
            }

            return true; // continue iteration
        });
        return result;
    }

    public StreetRouter (StreetLayer streetLayer) {
        this.streetLayer = streetLayer;
        // TODO one of two things: 1) don't hardwire drive-on-right, or 2) https://en.wikipedia.org/wiki/Dagen_H
        this.turnCostCalculator = new TurnCostCalculator(streetLayer, true);
    }

    /**
     * Finds closest vertex which has streetMode permissions
     *
     * @param lat Latitude in floating point (not fixed int) degrees.
     * @param lon Longitude in flating point (not fixed int) degrees.
     * @return true if edge was found near wanted coordinate
     */
    public boolean setOrigin (double lat, double lon) {
        Split split = streetLayer.findSplit(lat, lon, StreetLayer.LINK_RADIUS_METERS, streetMode);
        if (split == null) {
            LOG.info("No street was found near the specified origin point of {}, {}.", lat, lon);
            return false;
        }
        originSplit = split;
        bestStatesAtEdge.clear();
        queue.clear();
        // from vertex is at end of back edge. Set edge correctly so that turn restrictions/costs are applied correctly
        // at the origin.
        State startState0 = new State(split.vertex0, split.edge + 1, streetMode);
        State startState1 = new State(split.vertex1, split.edge, streetMode);
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
        State startState = new State(fromVertex, -1, streetMode);
        queue.add(startState);
    }

    /**
     * Adds multiple origins.
     *
     * Each state is one origin. Weight, durationSeconds and distance is copied from state.
     * If legMode is LegMode.BICYCLE_RENT state.isBikeShare is set to true
     *
     * @param previousStates map of bikeshares/P+Rs vertexIndexes and states Return of {@link #getReachedVertices(VertexStore.VertexFlag)}}
     * @param switchTime How many ms is added to state time (this is used when switching modes, renting bike, parking a car etc.)
     * @param switchCost This is added to the weight and is a cost of switching modes
     * @param legMode What origin search is this bike share or P+R
     */
    public void setOrigin(TIntObjectMap<State> previousStates, int switchTime, int switchCost, LegMode legMode) {
        bestStatesAtEdge.clear();
        queue.clear();

        previousStates.forEachEntry((vertexIdx, previousState) -> {
            // backEdge needs to be unique for each start state or they will wind up dominating each other.
            // subtract 1 from -vertexIdx because -0 == 0
            State state = new State(vertexIdx, -vertexIdx - 1, streetMode);
            state.weight = previousState.weight+switchCost;
            state.durationSeconds = previousState.durationSeconds+switchTime;
            if (legMode == LegMode.BICYCLE_RENT) {
                state.isBikeShare = true;
            }
            state.distance = previousState.distance;
            queue.add(state);
            return true;
        });

    }

    /**
     * Finds closest vertex which has streetMode permissions
     *
     * @param lat Latitude in floating point (not fixed int) degrees.
     * @param lon Longitude in flating point (not fixed int) degrees.
     * @return true if edge was found near wanted coordinate
     */
    public boolean setDestination (double lat, double lon) {
        this.destinationSplit = streetLayer.findSplit(lat, lon, StreetLayer.LINK_RADIUS_METERS, streetMode);
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

            if (dominanceVariable != State.RoutingVariable.DISTANCE_MILLIMETERS) {
                LOG.warn("Setting a distance limit when distance is not the dominance function, this is a resource limiting issue and paths may be incorrect.");
            }
        } else {
            //We need to set distance limit to largest possible value otherwise nothing would get routed
            //since first edge distance would be larger then 0 m and routing would stop
            distanceLimitMm = Integer.MAX_VALUE;
        }
        if (timeLimitSeconds > 0) {
            tmpTimeLimitSeconds = timeLimitSeconds;

            if (dominanceVariable != State.RoutingVariable.DURATION_SECONDS) {
                LOG.warn("Setting a time limit when time is not the dominance function, this is a resource limiting issue and paths may be incorrect.");
            }
        } else {
            //Same issue with time limit
            tmpTimeLimitSeconds = Integer.MAX_VALUE;
        }

        if (timeLimitSeconds > 0 && distanceLimitMeters > 0) {
            LOG.warn("Both distance limit of {}m and time limit of {}s are set in streetrouter", distanceLimitMeters, timeLimitSeconds);
        } else if (timeLimitSeconds == 0 && distanceLimitMeters == 0) {
            LOG.debug("Distance and time limit are set to 0 in streetrouter. This means NO LIMIT in searching so WHOLE of street graph will be searched. This can be slow.");
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
                    if (state.turnRestrictions == null)
                        continue QUEUE;
                    else if (s0.turnRestrictions != null && s0.turnRestrictions.size() == state.turnRestrictions.size()) {
                        // if they have the same turn restrictions, dominate this state with the one in the queue.
                        // if we make all turn-restricted states strictly incomparable we can get infinite loops with adjacent turn
                        // restrictions, see #88.
                        boolean[] same = new boolean [] { true };
                        s0.turnRestrictions.forEachEntry((ridx, pos) -> {
                            if (!state.turnRestrictions.containsKey(ridx) || state.turnRestrictions.get(ridx) != pos) same[0] = false;
                            return same[0]; // shortcut iteration if they're not the same
                        });

                        if (same[0]) continue QUEUE;
                    }
                }
            }

            if (toVertex > 0 && toVertex == s0.vertex) {
                // found destination
                break;
            }

            if (s0.getRoutingVariable(dominanceVariable) > bestValueAtDestination) break;

            // non-dominated state coming off the pqueue is by definition the best way to get to that vertex
            // but states in turn restrictions don't dominate anything, to avoid resource limiting issues
            if (s0.turnRestrictions != null)
                bestStatesAtEdge.put(s0.backEdge, s0);
            else {
                // we might need to save an existing state that is in a turn restriction so is codominant
                for (Iterator<State> it = bestStatesAtEdge.get(s0.backEdge).iterator(); it.hasNext();) {
                    State other = it.next();
                    if (s0.getRoutingVariable(dominanceVariable) < other.getRoutingVariable(dominanceVariable)) {
                        it.remove();
                    }

                    // avoid a ton of codominant states when there is e.g. duplicated OSM data
                    // However, save this state if it is not in a turn restriction and the other state is
                    else if (s0.getRoutingVariable(dominanceVariable) == other.getRoutingVariable(dominanceVariable) &&
                            !(other.turnRestrictions != null && s0.turnRestrictions == null))
                        continue QUEUE;
                }
                bestStatesAtEdge.put(s0.backEdge, s0);
            }

            if (routingVisitor != null) {
                routingVisitor.visitVertex(s0);
            }

            // if this state is at the destination, figure out the cost at the destination and use it for target pruning
            // by using getState(split) we include turn restrictions and turn costs. We've already addded this state
            // to bestStates so getState will be correct
            if (destinationSplit != null && (s0.vertex == destinationSplit.vertex0 || s0.vertex == destinationSplit.vertex1)) {
                State atDest = getState(destinationSplit);
                // atDest could be null even though we've found a nearby vertex because of a turn restriction
                if (atDest != null && bestValueAtDestination > atDest.getRoutingVariable(dominanceVariable)) {
                    bestValueAtDestination = atDest.getRoutingVariable(dominanceVariable);
                }
            }

            // explore edges leaving this vertex
            streetLayer.outgoingEdges.get(s0.vertex).forEach(eidx -> {
                edge.seek(eidx);

                State s1 = edge.traverse(s0, streetMode, profileRequest, turnCostCalculator);

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
        return states.stream().reduce((s0, s1) -> s0.getRoutingVariable(dominanceVariable) < s1.getRoutingVariable(dominanceVariable) ? s0 : s1).get();
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
            else if (ret.getRoutingVariable(dominanceVariable) > state.getRoutingVariable(dominanceVariable)) {
                ret = state;
            }
        }

        return ret;
    }

    public int getTravelTimeToVertex (int vertexIndex) {
        State state = getStateAtVertex(vertexIndex);
        return state != null ? state.durationSeconds : Integer.MAX_VALUE;
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
        // get all the states at all the vertices
        List<State> relevantStates = new ArrayList<>();

        EdgeStore.Edge e = streetLayer.edgeStore.getCursor(split.edge);

        for (TIntIterator it = streetLayer.incomingEdges.get(split.vertex0).iterator(); it.hasNext();) {
            Collection<State> states = bestStatesAtEdge.get(it.next());
            // NB this needs a state to copy turn restrictions into. We then don't use that state, which is fine because
            // we don't need the turn restrictions any more because we're at the end of the search
            states.stream().filter(s -> e.canTurnFrom(s, new State(-1, split.edge, s)))
                    .map(s -> {
                        State ret = new State(-1, split.edge, s);
                        ret.streetMode = s.streetMode;

                        // figure out the turn cost
                        int turnCost = this.turnCostCalculator.computeTurnCost(s.backEdge, split.edge, s.streetMode);
                        int traversalCost = (int) Math.round(split.distance0_mm / 1000d / e.calculateSpeed(profileRequest, s.streetMode));

                        // TODO length of perpendicular
                        ret.incrementWeight(turnCost + traversalCost);
                        ret.incrementTimeInSeconds(turnCost + traversalCost);
                        ret.distance += split.distance0_mm;

                        return ret;
                    })
                    .forEach(relevantStates::add);
        }

        // advance to back edge
        e.advance();

        for (TIntIterator it = streetLayer.incomingEdges.get(split.vertex1).iterator(); it.hasNext();) {
            Collection<State> states = bestStatesAtEdge.get(it.next());
            states.stream().filter(s -> e.canTurnFrom(s, new State(-1, split.edge + 1, s)))
                    .map(s -> {
                        State ret = new State(-1, split.edge + 1, s);
                        ret.streetMode = s.streetMode;

                        // figure out the turn cost
                        int turnCost = this.turnCostCalculator.computeTurnCost(s.backEdge, split.edge + 1, s.streetMode);
                        int traversalCost = (int) Math.round(split.distance1_mm / 1000d / e.calculateSpeed(profileRequest, s.streetMode));
                        ret.distance += split.distance1_mm;

                        // TODO length of perpendicular
                        ret.incrementWeight(turnCost + traversalCost);
                        ret.incrementTimeInSeconds(turnCost + traversalCost);

                        return ret;
                    })
                    .forEach(relevantStates::add);
        }

        return relevantStates.stream()
                .reduce((s0, s1) -> s0.getRoutingVariable(dominanceVariable) < s1.getRoutingVariable(dominanceVariable) ? s0 : s1)
                .orElse(null);
    }

    public Split getDestinationSplit() {
        return destinationSplit;
    }

    /**
     * Returns state with smaller weight to vertex0 or vertex1
     *
     * First split is called with streetMode Mode
     *
     * If state to only one vertex exists return that vertex.
     * If state to none of the vertices exists returns null
     * @return
     */
    public State getState(double lat, double lon) {
        Split split = streetLayer.findSplit(lat, lon, StreetLayer.LINK_RADIUS_METERS, streetMode);
        if (split == null) {
            LOG.info("No street was found near the specified origin point of {}, {}.", lat, lon);
            return null;
        }
        return getState(split);
    }

    public static class State implements Cloneable {
        public int vertex;
        public int weight;
        public int backEdge;

        protected int durationSeconds;
        //Distance in mm
        public int distance;
        public StreetMode streetMode;
        public State backState; // previous state in the path chain
        public boolean isBikeShare = false; //is true if vertex in this state is Bike sharing station where mode switching occurs

        /**
         * turn restrictions we are in the middle of.
         * Value is how many edges of this turn restriction we have traversed so far, so if 1 we have traversed only the from edge, etc.
         */
        public TIntIntMap turnRestrictions;

        public State(int atVertex, int viaEdge, State backState) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            this.backState = backState;
            this.distance = backState.distance;
            this.durationSeconds = backState.durationSeconds;
            this.weight = backState.weight;
        }

        public State(int atVertex, int viaEdge, StreetMode streetMode) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            this.backState = null;
            this.distance = 0;
            this.streetMode = streetMode;
            this.durationSeconds = 0;
        }


        public void incrementTimeInSeconds(long seconds) {
            if (seconds < 0) {
                LOG.warn("A state's time is being incremented by a negative amount while traversing edge "
                    );
                //defectiveTraversal = true;
                return;
            }
            durationSeconds += seconds;
        }

        public int getDurationSeconds() {
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

        public int getRoutingVariable (RoutingVariable variable) {
            if (variable == null) throw new NullPointerException("Routing variable is null");

            switch (variable) {
                case DURATION_SECONDS:
                    return this.durationSeconds;
                case WEIGHT:
                    return this.weight;
                case DISTANCE_MILLIMETERS:
                    return this.distance;
                default:
                    throw new IllegalStateException("Unknown routing variable");
            }
        }

        public static enum RoutingVariable {
            /** Time, in seconds */
            DURATION_SECONDS,

            /** Weight/generalized cost (this is what is actually used to find paths */
            WEIGHT,

            /** Distance, in millimeters */
            DISTANCE_MILLIMETERS
        }
    }
}
