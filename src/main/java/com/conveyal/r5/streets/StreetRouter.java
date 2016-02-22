package com.conveyal.r5.streets;

import com.conveyal.r5.profile.Mode;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.util.TIntObjectHashMultimap;
import com.conveyal.r5.util.TIntObjectMultimap;
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

    // TODO we might be able to make this more efficient by taking advantage of the fact that we almost always have a
    // single state per vertex (the only time we don't is when we're in the middle of a turn restriction).
    TIntObjectMultimap<State> bestStates = new TIntObjectHashMultimap<>();

    PriorityQueue<State> queue = new PriorityQueue<>((s0, s1) -> s0.weight - s1.weight);

    // If you set this to a non-negative number, the search will be directed toward that vertex .
    public int toVertex = ALL_VERTICES;

    /** Set individual properties here, or an entirely new request */
    public ProfileRequest profileRequest = new ProfileRequest();

    /** Search mode: we need a single mode, it is up to the caller to disentagle the modes set in the profile request */
    public Mode mode = Mode.WALK;

    private RoutingVisitor routingVisitor;

    private Split originSplit;

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
            State state = getState(streetVertex);
            // TODO should this be time?
            if (state != null) result.put(stop, state.weight);
            return true; // continue iteration
        });

        return result;
    }

    /** Return a map where the keys are all the reached vertices, and the values are their distances from the origin. */
    public TIntIntMap getReachedVertices () {
        TIntIntMap result = new TIntIntHashMap();
        bestStates.forEachEntry((vidx, states) -> {
            State state = states.stream().reduce((s0, s1) -> s0.weight < s1.weight ? s0 : s1).get();
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
        bestStates.forEachEntry((vertexIndex, states) -> {
            VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(vertexIndex);
            if (vertex.getFlag(flag)) {
                State state = states.stream().reduce((s0, s1) -> s0.weight < s1.weight ? s0 : s1).get();
                result.put(vertexIndex, state);
            }
            return true;
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
        bestStates.forEachEntry((vertexIndex, states) -> {
            State state = states.stream().reduce((s0, s1) -> s0.weight < s1.weight ? s0 : s1).get();
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
     * @return true if edge was found near wanted coordinate
     */
    public boolean setOrigin (double lat, double lon) {
        Split split = streetLayer.findSplit(lat, lon, 500);
        if (split == null) {
            LOG.info("No street was found near the specified origin point of {}, {}.", lat, lon);
            return false;
        }
        originSplit = split;
        bestStates.clear();
        queue.clear();
        State startState0 = new State(split.vertex0, -1, profileRequest.getFromTimeDate(), mode);
        State startState1 = new State(split.vertex1, -1, profileRequest.getFromTimeDate(), mode);
        // TODO walk speed, assuming 1 m/sec currently.
        startState0.weight = split.distance0_mm / 1000;
        startState1.weight = split.distance1_mm / 1000;
        // NB not adding to bestStates, as it will be added when it comes out of the queue
        queue.add(startState0);
        queue.add(startState1);
        return true;
    }

    public void setOrigin (int fromVertex) {
        bestStates.clear();
        queue.clear();
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
        bestStates.clear();
        queue.clear();
        bikeStations.forEachEntry((vertexIdx, bikeStationState) -> {
           State state = new State(vertexIdx, -1, bikeStationState.getTime()+switchTime, mode);
            state.weight = bikeStationState.weight+switchCost;
            state.isBikeShare = true;
            queue.add(state);
            return true;
        });

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

        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();
        QUEUE: while (!queue.isEmpty()) {
            State s0 = queue.poll();

            if (DEBUG_OUTPUT) {
                VertexStore.Vertex v = streetLayer.vertexStore.getCursor(s0.vertex);
                printStream.println(String.format("%.6f,%.6f,%d", v.getLat(), v.getLon(), s0.weight));
            }
            if (bestStates.containsKey(s0.vertex)) {
                for (State state : bestStates.get(s0.vertex)) {
                    // states in turn restrictions don't dominate anything
                    if (!state.inTurnRestriction) continue QUEUE;
                }
            }

            if (toVertex > 0 && toVertex == s0.vertex) {
                // found destination
                break;
            }

            // non-dominated state coming off the pqueue is by definition the best way to get to that vertex
            // but states in turn restrictions don't dominate anything, to avoid resource limiting issues
            if (s0.inTurnRestriction)
                bestStates.put(s0.vertex, s0);
            else {
                // we might need to save an existing state that is in a turn restriction so is codominant
                for (Iterator<State> it = bestStates.get(s0.vertex).iterator(); it.hasNext();) {
                    State other = it.next();
                    if (s0.weight < other.weight) it.remove();
                    else if (s0.weight == other.weight) continue QUEUE; // avoid a ton of codominant states when there is e.g. duplicated OSM data
                }
                bestStates.put(s0.vertex, s0);
            }

            if (routingVisitor != null) {
                routingVisitor.visitVertex(s0);
            }

            // explore edges leaving this vertex
            streetLayer.outgoingEdges.get(s0.vertex).forEach(eidx -> {
                edge.seek(eidx);

                State s1 = edge.traverse(s0, mode, profileRequest);

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

    /** get a single best state at a vertex. There can be more than one state at a vertex due to turn restrictions */
    public State getState (int vertexIndex) {
        Collection<State> states = bestStates.get(vertexIndex);
        if (states.isEmpty()) {
            return null; // Unreachable
        }

        // get the lowest weight, even if it's in the middle of a turn restriction
        return states.stream().reduce((s0, s1) -> s0.weight < s1.weight ? s0 : s1).get();
    }

    public int getTravelTimeToVertex (int vertexIndex) {
        State state = getState(vertexIndex);

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
        State weight0 = getState(split.vertex0);
        State weight1 = getState(split.vertex1);
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

    public static class State implements Cloneable {
        public int vertex;
        public int weight;
        public int backEdge;
        // the current time at this state, in milliseconds UNIX time
        protected Instant time;
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
            this.time = Instant.ofEpochMilli(fromTimeDate);
            this.distance = backState.distance;
            this.durationSeconds = backState.durationSeconds;
        }

        public State(int atVertex, int viaEdge, long fromTimeDate, Mode mode) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            this.backState = null;
            this.time = Instant.ofEpochMilli(fromTimeDate);
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
            if (false) {
                time = time.minusSeconds(seconds);
                durationSeconds+=seconds;
            } else {
                time = time.plusSeconds(seconds);
                durationSeconds+=seconds;
            }
        }

        public int getDurationSeconds() {
            return durationSeconds;
        }

        public long getTime() {
            return time.toEpochMilli();
        }

        public void incrementWeight(float weight) {
            this.weight+=(int)weight;
        }
    }

}
