package com.conveyal.r5.streets;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.common.SphericalDistanceLibrary;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.TIntObjectHashMultimap;
import com.conveyal.r5.util.TIntObjectMultimap;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.apache.commons.math3.util.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

import static com.conveyal.r5.streets.LinkedPointSet.OFF_STREET_SPEED_MILLIMETERS_PER_SECOND;

/**
 * This routes over the street layer of a TransitNetwork.
 * It is a throw-away calculator object that retains routing state after the search is finished.
 * Additional functions are called to retrieve the routing results from that state.
 */
public class StreetRouter {

    private static final Logger LOG = LoggerFactory.getLogger(StreetRouter.class);

    private static final boolean DEBUG_OUTPUT = false;

    /** A special value for the search target vertex: do not stop the search at any particular vertex. */
    public static final int ALL_VERTICES = -1;

    /** The StreetLayer to route on. */
    public final StreetLayer streetLayer;

    /**
     * True if this is a search to find transit stops.
     * If true, the search will stop when the quantity of stops found is maxStops,
     * or earlier if the queue is empty or if the time or distance limit is hit.
     */
    public boolean transitStopSearch = false;

    /**
     * If this is non-null, search for vertices with specific flags, e.g. bike share or park and ride.
     * The search will stop when the quantity of vertices found with that flag reaches flagSearchQuantity,
     * or earlier if the queue is empty or the time or distance limit is reached.
     */
    public VertexStore.VertexFlag flagSearch = null;

    /**
     * How many transit stops should we find
     */
    public int transitStopSearchQuantity = PointToPointQuery.MAX_ACCESS_STOPS;

    /**
     * How many vertices with flags should we find
     */
    public int flagSearchQuantity = 20;

    /**
     * The reason this is pluggable is to account for left and right hand drive (as well as any other country-specific
     * details you might want to implement)
     */
    public TraversalTimeCalculator timeCalculator;

    // These are used for scaling coordinates in approximate distance calculations.
    // The lon value must be properly scaled to underestimate distances in the region where we're routing.
    private static final double MM_PER_UNIT_LAT_FIXED =
            (SphericalDistanceLibrary.EARTH_CIRCUMFERENCE_METERS * 1000) / (360 * VertexStore.FIXED_FACTOR);
    private double millimetersPerUnitLonFixed;
    // Yes, that's indeed the speed unit "seconds per millimeter", to avoid computing 1/x repeatedly.
    private double maxSpeedSecondsPerMillimeter;

    /**
     * The StreetRouter will respect any nonzero limits, and will stop the search when it hits either of them.
     * If both limits are zero a warning will be logged. If both are set, both are used, but you should never do this.
     */
    public int distanceLimitMeters = 0;
    public int timeLimitSeconds = 0;

    /**
     * What routing variable (time or distance) should be used to decide when a path is better than another.
     * Only one such variable is optimized in a given search. It's algorithmically invalid to prune or otherwise discard
     * any path based on any other characteristic of the path but this one, e.g. you should not set a distance limit
     * when the quantityToMinimize is duration. (Though discarding based on characteristics of the current edge and
     * search-wide settings is fine). Pruning based on other path characteristics is referred to in the literature as a
     * "resource limiting" problem and requires computing pareto-optimal paths, which we don't do on the street network.
     */
    public State.RoutingVariable quantityToMinimize = State.RoutingVariable.DURATION_SECONDS;

    /**
     * Store the best state at the end of each edge. Although we think of states being located at vertices, from a
     * routing point of view they are located just before the vertex, at the end of one of the edges coming into that
     * vertex. This allows us to apply turn costs.
     *
     * You can't properly apply turn costs (which are vertex costs) when you are storing a single state at each vertex,
     * because the vertex cost is not applied until leaving the vertex. This means that a state that must make
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
     * removed it. Using edge-based graphs to represent turn costs/restrictions is documented at
     * http://geo.fsv.cvut.cz/gdata/2013/pin2/d/dokumentace/line_graph_teorie.pdf
     *
     * Though we imagine having multiple states at any vertex, we represent this by associating the states with directed
     * edges, at it seems at first that you would need to keep only one state per directed edge (at its end).
     * However, OSM contains complex turn restrictions that have via ways. This could be a simple U-turn on a dual
     * carriageway, but could also be something more complex (no right turn after left &c.). In these cases, we still
     * have to have incomparable states (states that cannot be dominated by other paths with different characteristics)
     * when we are partway through the restriction.
     *
     * When determining the weight at a vertex, one should just grab the states at the ends of all the incoming edges,
     * and take the minimum. However, turn costs make it a bit more complicated to properly determine the time/weight
     * at a destination along a road that is not represented by a vertex. Suppose that the best state at one of the
     * endpoint vertices of the destination edge requires a left turn onto that destination edge. It is important to
     * apply that left turn cost. Even more important is to make sure that the destination edge is not the end of a
     * restricted turn; if it is, one must reach the destination via an alternate state.
     *
     * TODO we might be able to make this more efficient (not using a multimap)
     * by taking advantage of the fact that we almost always have a single state per edge
     * (the only time we don't is when we're in the middle of a turn restriction).
     */
    TIntObjectMultimap<State> bestStatesAtEdge = new TIntObjectHashMultimap<>();

    // The queue is prioritized by the specified optimization objective variable.
    PriorityQueue<State> queue = new PriorityQueue<>(
            Comparator.comparingInt(s0 -> (s0.getRoutingVariable(quantityToMinimize) + s0.heuristic)));

    /**
     * If you set this to a non-negative number, the search will end at the vertex with the given index,
     * and will be directed toward that vertex.
     */
    public int toVertex = ALL_VERTICES;

    /** Set individual properties here, or an entirely new request FIXME <-- what does this comment mean? */
    public ProfileRequest profileRequest = new ProfileRequest();

    /**
     * Mode of transport used in this search. This router requires a single mode, so it is up to the caller to
     * run several street searches in sequence if that's what the profileRequest requires.
     */
    public StreetMode streetMode = StreetMode.WALK;

    // Allows supplying callbacks for monitoring search progress for debugging and visualization purposes.
    private RoutingVisitor routingVisitor;

    private Split originSplit;

    private Split destinationSplit;

    // The best known value of the chosen objective variable at the destination, used to prune the search.
    private int bestValueAtDestination = Integer.MAX_VALUE;

    // This is the maximum absolute latitude of origin, when there are multiple origin states.
    // It is used when calculating A* goal direction heuristic to ensure that we underestimate distances.
    // FIXME no, we'd need to use the max latitude of any vertex in the street network. Do we need goal direction at all?
    private int maxAbsOriginLat = Integer.MIN_VALUE;

    /**
     * The preceding StreetRouter in a multi-router search.
     * For example if we are renting a bike we need three street searches:
     * First walking from the origin to all bike rental stations, then biking to other bike rental stations, then
     * walking from the final bike rental station to the destination.
     */
    public StreetRouter previousRouter;

    /**
     * Supply a RoutingVisitor to track search progress for debugging.
     */
    public void setRoutingVisitor(RoutingVisitor routingVisitor) {
        this.routingVisitor = routingVisitor;
    }

    /**
     * After a search has been run, calling this method will returns a map from transit stop indexes to the value of
     * the objective variable for the optimal path to that stop. TransitLayer contains the information about which
     * street vertices are transit stops.
     *
     * Currently this has what appears to be an optimization that uses the stops found by the {@link StopVisitor}
     * itself, if the routingVisitor used by the search is a StopVisitor. This is the case when transitStopSearch
     * is true. The point of this whole thing is to limit the number of stops found, which was a hack to keep
     * profile routing reasonably fast back in the day. Current SuboptimalPathRouting may not need this anymore.
     * FIXME remove limits on the number of transit stops found, then remove this special case for StopVisitor
     */
    public TIntIntMap getReachedStops() {
        if (transitStopSearch && routingVisitor instanceof StopVisitor) {
            return ((StopVisitor) routingVisitor).getStops();
        }
        TIntIntMap result = new TIntIntHashMap();
        TransitLayer transitLayer = streetLayer.parentNetwork.transitLayer;
        transitLayer.stopForStreetVertex.forEachEntry((streetVertex, stop) -> {
            if (streetVertex == -1) return true;
            State state = getStateAtVertex(streetVertex);
            if (state != null) result.put(stop, state.getRoutingVariable(quantityToMinimize));
            return true; // continue iteration
        });
        return result;
    }

    /**
     * Return a map where the keys are all the reached vertices, and the values are the value of the optimization
     * objective variable for the optimal path to that vertex.
     */
    public TIntIntMap getReachedVertices () {
        TIntIntMap result = new TIntIntHashMap();
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor();
        bestStatesAtEdge.forEachEntry((eidx, states) -> {
            if (eidx < 0) return true;
            // Iterating over a little list and reducing the values with a stream might be slow.
            // TODO We should try replacing this with states.get(0) and see if it makes building distance tables faster.
            State state = states.stream()
                    .reduce((s0, s1) -> s0.getRoutingVariable(quantityToMinimize) < s1.getRoutingVariable(quantityToMinimize) ? s0 : s1).get();
            e.seek(eidx);
            int vidx = e.getToVertex();

            if (!result.containsKey(vidx) || result.get(vidx) > state.getRoutingVariable(quantityToMinimize))
                result.put(vidx, state.getRoutingVariable(quantityToMinimize));

            return true; // continue iteration
        });
        return result;
    }

    /**
     * After a search has been run, calling this method will returns a map from vertex indexes to the value of
     * the objective variable for the optimal path to that vertex, but only for vertices with a certain flag set.
     *
     * There is currently an optimization that makes a special case where {@link VertexFlagVisitor}
     * was used. We're not sure that's necessary, see javadoc on getReachedStops. This one returns the states
     * themselves, while the others return objective variable values.
     *
     * @return a map where all the keys are vertex indexes with the particular flag and all the values are states.
     */
    public TIntObjectMap<State> getReachedVertices (VertexStore.VertexFlag flag) {
        if (flagSearch == flag && routingVisitor instanceof VertexFlagVisitor) {
            return ((VertexFlagVisitor) routingVisitor).getVertices();
        }
        TIntObjectMap<State> result = new TIntObjectHashMap<>();
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor();
        VertexStore.Vertex v = streetLayer.vertexStore.getCursor();
        bestStatesAtEdge.forEachEntry((eidx, states) -> {
            if (eidx < 0) return true;

            State state = states.stream().reduce((s0, s1) ->
                    s0.getRoutingVariable(quantityToMinimize) < s1.getRoutingVariable(quantityToMinimize) ? s0 : s1).get();
            e.seek(eidx);
            int vidx = e.getToVertex();
            v.seek(vidx);

            if (v.getFlag(flag)) {
                if (!result.containsKey(vidx) || result.get(vidx).getRoutingVariable(quantityToMinimize) >
                                                            state.getRoutingVariable(quantityToMinimize)) {
                    result.put(vidx, state);
                }
            }

            return true; // continue iteration
        });
        return result;
    }

    public StreetRouter (StreetLayer streetLayer) {
        this.streetLayer = streetLayer;
        this.timeCalculator = streetLayer.edgeStore.edgeTraversalTimes;
        // If no per-edge timings were supplied in the network, fall back on simple default timings
        if (this.timeCalculator == null) {
            // TODO one of two things: 1) don't hardwire drive-on-right, or 2) https://en.wikipedia.org/wiki/Dagen_H
            this.timeCalculator = new BasicTraversalTimeCalculator(streetLayer, true);
        }
    }


    /**
     * Set the origin point of this StreetRouter (before a search is started) to a point along an edge that allows
     * traversal by the specified streetMode.
     *
     * If the given point is not close to an existing vertex, we will create two states, one at each vertex at the
     * ends of the edge that is found.
     *
     * Note that the mode of travel should be set before calling this, otherwise you may link to a road you can't
     * travel on!
     *
     * @param lat Latitude in floating point (not fixed int) degrees.
     * @param lon Longitude in floating point (not fixed int) degrees.
     * @return true if an edge was found near the specified coordinate
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
        // The states are located at the end of edges. Vertex0 is at the end of the reverse edge (split.edge + 1).
        // In these states we must specify which edge was traversed to reach them, so that turn costs work.
        State startState0 = new State(split.vertex0, split.edge + 1, streetMode);
        State startState1 = new State(split.vertex1, split.edge, streetMode);
        EdgeStore.Edge  edge = streetLayer.edgeStore.getCursor(split.edge);
        int offStreetTime = split.distanceToEdge_mm / OFF_STREET_SPEED_MILLIMETERS_PER_SECOND;

        // Uses weight based on distance from end vertices, and speed on edge which depends on transport mode
        float speedMetersPerSecond = edge.calculateSpeed(profileRequest, streetMode);
        startState1.durationSeconds = (int) ((split.distance1_mm / 1000) / speedMetersPerSecond) + offStreetTime;
        startState1.distance = split.distance1_mm + split.distanceToEdge_mm;
        edge.advance();

        // Speed can be different on opposite sides of the same street
        speedMetersPerSecond = edge.calculateSpeed(profileRequest, streetMode);
        startState0.durationSeconds = (int) ((split.distance0_mm / 1000) / speedMetersPerSecond) + offStreetTime;
        startState0.distance = split.distance0_mm + split.distanceToEdge_mm;

        // FIXME Below is reversing the vertices, but then aren't the weights, times, distances wrong? Why are we even doing this?
        if (profileRequest.reverseSearch) {
             startState0.vertex = split.vertex1;
             startState1.vertex = split.vertex0;
        }

        // If there is a turn restriction on this edge, we need to indicate that we are beginning the search in a
        // turn restriction.
        streetLayer.edgeStore.startTurnRestriction(streetMode, profileRequest.reverseSearch, startState0);
        streetLayer.edgeStore.startTurnRestriction(streetMode, profileRequest.reverseSearch, startState1);

        // These initial states are not recorded as bestStates, they will be added when they come out of the queue.
        // FIXME but wait - we are putting them in the bestStates for some reason.
        queue.add(startState0);
        queue.add(startState1);
        bestStatesAtEdge.put(startState0.backEdge, startState0);
        bestStatesAtEdge.put(startState1.backEdge, startState1);

        maxAbsOriginLat = originSplit.fixedLat;
        return true;
    }

    public void setOrigin (int fromVertex) {
        bestStatesAtEdge.clear();
        queue.clear();

        // sets maximal absolute origin latitude used for goal direction heuristic
        VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(fromVertex);
        maxAbsOriginLat = vertex.getFixedLat();

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
     * @param switchTime How many s is added to state time (this is used when switching modes, renting bike, parking a car etc.)
     * @param switchCost This is added to the weight and is a cost of switching modes
     * @param legMode What origin search is this bike share or P+R
     */
    public void setOrigin(TIntObjectMap<State> previousStates, int switchTime, int switchCost, LegMode legMode) {
        bestStatesAtEdge.clear();
        queue.clear();
        //Maximal origin latitude is used in goal direction heuristic.
        final int[] maxOriginLatArr = { Integer.MIN_VALUE };

        previousStates.forEachEntry((vertexIdx, previousState) -> {
            // backEdge needs to be unique for each start state or they will wind up dominating each other.
            // subtract 1 from -vertexIdx because -0 == 0
            State state = new State(vertexIdx, previousState.backEdge, streetMode);
            state.durationSeconds = previousState.durationSeconds;
            state.incrementTimeInSeconds(switchTime);
            if (legMode == LegMode.BICYCLE_RENT) {
                state.isBikeShare = true;
            }
            state.distance = previousState.distance;
            if (!isDominated(state)) {
                bestStatesAtEdge.put(state.backEdge, state);
                queue.add(state);
                VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(state.vertex);
                int deltaLatFixed = vertex.getFixedLat();
                maxOriginLatArr[0] = Math.max(maxOriginLatArr[0], Math.abs(deltaLatFixed));
            }
            return true;
        });
        maxAbsOriginLat = maxOriginLatArr[0];

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
     * Call one of the setOrigin functions first before calling route().
     * Don't call route() more than once, a StreetRouter is only intended to be used once.
     * Routing will respect any nonzero limits (distance or time), and will stop the search when it hits either of them.
     * If both limits are zero a warning will be logged. If both are set, both are used, but you should not do this
     * because it always implies a resource limiting problem.
     */
    public void route () {

        long startTime = System.currentTimeMillis();

        final int distanceLimitMm;
        //This is needed otherwise timeLimitSeconds gets changed and
        // on next call of route on same streetRouter wrong warnings are returned
        // (since timeLimitSeconds is MAX_INTEGER not 0)
        // FIXME this class is supposed to be throw-away, should we be reusing instances at all? change this variable name to be clearer.
        final int tmpTimeLimitSeconds;

        // Set up goal direction.
        if (destinationSplit != null) {
            // This search has a destination, so enable A* goal direction.
            // To speed up the distance calculations that are part of the A* heuristic, we precalculate some factors.
            // We want to scale X distances by the cosine of the higher of the two latitudes to underestimate distances,
            // as required for the A* heuristic to be admissible.
            // TODO this should really use the max latitude of the whole street layer.
            int maxAbsLatFixed = Math.max(Math.abs(destinationSplit.fixedLat), Math.abs(maxAbsOriginLat));
            double maxAbsLatRadians = Math.toRadians(VertexStore.fixedDegreesToFloating(maxAbsLatFixed));
            millimetersPerUnitLonFixed = MM_PER_UNIT_LAT_FIXED * Math.cos(maxAbsLatRadians);
            // FIXME account for speeds of individual street segments, not just speed in request
            double maxSpeedMetersPerSecond = profileRequest.getSpeedForMode(streetMode);
            // Car speed is currently often unspecified in the request and defaults to zero.
            if (maxSpeedMetersPerSecond == 0) maxSpeedMetersPerSecond = 36.11; // 130 km/h
            maxSpeedSecondsPerMillimeter = 1 / (maxSpeedMetersPerSecond * 1000);
        }

        if (distanceLimitMeters > 0) {
            // Distance in State is in millimeters. Distance limit is in meters, requiring a conversion.
            distanceLimitMm = distanceLimitMeters * 1000;
            if (quantityToMinimize != State.RoutingVariable.DISTANCE_MILLIMETERS) {
                LOG.warn("Setting a distance limit when distance is not the dominance function, this is a resource limiting issue and paths may be incorrect.");
            }
        } else {
            // There is no distance limit. Set it to the largest possible value to allow routing to progress.
            distanceLimitMm = Integer.MAX_VALUE;
        }

        if (timeLimitSeconds > 0) {
            tmpTimeLimitSeconds = timeLimitSeconds;
            if (quantityToMinimize != State.RoutingVariable.DURATION_SECONDS) {
                LOG.warn("Setting a time limit when time is not the dominance function, this is a resource limiting issue and paths may be incorrect.");
            }
        } else {
            // There is no time limit. Set it to the largest possible value to allow routing to progress.
            tmpTimeLimitSeconds = Integer.MAX_VALUE;
        }

        if (timeLimitSeconds > 0 && distanceLimitMeters > 0) {
            LOG.warn("Both distance limit of {}m and time limit of {}s are set in StreetRouter", distanceLimitMeters, timeLimitSeconds);
        } else if (timeLimitSeconds == 0 && distanceLimitMeters == 0) {
            LOG.debug("Distance and time limit are both set to 0 in StreetRouter. This means NO LIMIT in searching so the entire street graph will be explored. This can be slow.");
        } else if (distanceLimitMeters > 0) {
            LOG.debug("Using distance limit of {} meters", distanceLimitMeters);
        } else if (timeLimitSeconds > 0) {
            LOG.debug("Using time limit of {} sec", timeLimitSeconds);
        }

        if (queue.size() == 0) {
            LOG.warn("Routing without first setting an origin, no search will happen.");
        }

        PrintStream debugPrintStream = null;
        if (DEBUG_OUTPUT) {
            File debugFile = new File(String.format("street-router-debug.csv"));
            OutputStream outputStream;
            try {
                outputStream = new BufferedOutputStream(new FileOutputStream(debugFile));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            debugPrintStream = new PrintStream(outputStream);
            debugPrintStream.println("lat,lon,weight");
        }

        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();

        if (transitStopSearch) {
            routingVisitor = new StopVisitor(streetLayer, quantityToMinimize, transitStopSearchQuantity, profileRequest.getMinTimeSeconds(streetMode));
        } else if (flagSearch != null) {
            routingVisitor = new VertexFlagVisitor(streetLayer, quantityToMinimize, flagSearch, flagSearchQuantity, profileRequest.getMinTimeSeconds(streetMode));
        }
        while (!queue.isEmpty()) {
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

                debugPrintStream.println(String.format("%.6f,%.6f,%d", v.getLat(), v.getLon(), s0.durationSeconds));
            }

            // The state coming off the priority queue may have been dominated by some other state that was produced
            // by traversing the same edge. Check that the state coming off the queue has not been dominated before
            // exploring it. States at the origin may have their backEdge set to a negative number to indicate that
            // they have no backEdge (were not produced by traversing an edge). Skip the check for those states.
            if (s0.backEdge >= 0 && !bestStatesAtEdge.get(s0.backEdge).contains(s0)) continue;

            // If the search has reached the destination, the state coming off the queue is the best way to get there.
            if (toVertex > 0 && toVertex == s0.vertex) break;

            // End the search if the state coming off the queue has exceeded the best-known cost to reach the destination.
            // TODO how important is this? How can this even happen? In a street search, is target pruning even effective?
            if (s0.getRoutingVariable(quantityToMinimize) > bestValueAtDestination) break;

            // Hit RoutingVistor callbacks to monitor search progress.
            if (routingVisitor != null) {
                routingVisitor.visitVertex(s0);

                if (routingVisitor.shouldBreakSearch()) {
                    LOG.debug("{} routing visitor stopped search", routingVisitor.getClass().getSimpleName());
                    queue.clear();
                    break;
                }
            }

            // If this state is at the destination, figure out the cost at the destination and use it for target pruning.
            // TODO explain what "target pruning" is in this context and why we need it. It seems that this is mainly about traversing split streets.
            // By using getState(split) we include turn restrictions and turn costs.
            // We've already added this state to bestStates so getState will be correct.
            if (destinationSplit != null && (s0.vertex == destinationSplit.vertex0 || s0.vertex == destinationSplit.vertex1)) {
                State atDest = getState(destinationSplit);
                // atDest could be null even though we've found a nearby vertex because of a turn restriction
                if (atDest != null && bestValueAtDestination > atDest.getRoutingVariable(quantityToMinimize)) {
                    bestValueAtDestination = atDest.getRoutingVariable(quantityToMinimize);
                }
            }

            TIntList edgeList;
            if (profileRequest.reverseSearch) {
                edgeList = streetLayer.incomingEdges.get(s0.vertex);
            } else {
                edgeList = streetLayer.outgoingEdges.get(s0.vertex);
            }
            // explore edges leaving this vertex
            edgeList.forEach(eidx -> {
                edge.seek(eidx);
                State s1 = edge.traverse(s0, streetMode, profileRequest, timeCalculator);
                if (s1 != null && s1.distance <= distanceLimitMm && s1.getDurationSeconds() < tmpTimeLimitSeconds) {
                    if (!isDominated(s1)) {
                        // Calculate the heuristic (which involves a square root) only when the state is retained.
                        s1.heuristic = calcHeuristic(s1);
                        bestStatesAtEdge.put(s1.backEdge, s1);
                        queue.add(s1);
                    }
                }
                return true; // Iteration over the edge list should continue.
            });
        }
        if (DEBUG_OUTPUT) {
            debugPrintStream.close();
        }
        long routingTimeMsec = System.currentTimeMillis() - startTime;
        LOG.debug("Routing took {} msec", routingTimeMsec);
    }

    /**
     * Given a new state, check whether it is dominated by any existing state that resulted from traversing the
     * same edge. Side effect: Boot out any existing states that are dominated by the new one.
     */
    private boolean isDominated(State newState) {
        // States in turn restrictions are incomparable (don't dominate and aren't dominated by other states)
        // If the new state is not in a turn restriction, check whether it dominates any existing states and remove them.
        // Multimap returns empty list for missing keys.
        for (Iterator<State> it = bestStatesAtEdge.get(newState.backEdge).iterator(); it.hasNext(); ) {
            State existingState = it.next();
            if (dominates(existingState, newState)) {
                // If any existing state dominates the new one, bail out early and declare the new state dominated.
                // We want to check if the existing state dominates the new one before the other way around because
                // when states are equal, the existing one should win (and the special case for turn restrictions).
                return true;
            } else if (dominates(newState, existingState)) {
                it.remove();
            }
        }
        return false; // Nothing existing has dominated this new state: it's non-dominated.
    }

    /**
     * Provide an underestimate on the remaining distance/weight/time to the destination (the A* heuristic).
     */
    private int calcHeuristic (State state) {
        // If there's no destination, there's no goal direction. Zero is always a valid underestimate.
        if (destinationSplit == null) return 0;
        VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(state.vertex);
        int deltaLatFixed = destinationSplit.fixedLat - vertex.getFixedLat();
        int deltaLonFixed = destinationSplit.fixedLon - vertex.getFixedLon();
        double millimetersX = millimetersPerUnitLonFixed * deltaLonFixed;
        double millimetersY = MM_PER_UNIT_LAT_FIXED * deltaLatFixed;
        double distanceMillimeters = FastMath.sqrt(millimetersX * millimetersX + millimetersY * millimetersY);
        double estimate = distanceMillimeters;
        if (quantityToMinimize != State.RoutingVariable.DISTANCE_MILLIMETERS) {
            // Calculate time in seconds to traverse this distance in a straight line.
            // Weight should always be greater than or equal to time in seconds.
            estimate *= maxSpeedSecondsPerMillimeter;
        }
        return (int) estimate;
    }

    /**
     * @return true if s1 is better *or equal* to s2, otherwise return false.
     */
    private boolean dominates (State s1, State s2) {
        if (s1.turnRestrictions == null && s2.turnRestrictions == null) {
            // The simple case where neither state has turn restrictions.
            // Note this is <= rather than < because we want an existing state with the same weight to beat a new one.
            return s1.getRoutingVariable(quantityToMinimize) <= s2.getRoutingVariable(quantityToMinimize);
        }
        // At least one of the states has turn restrictions.
        // Generally, a state with turn restrictions cannot dominate another state and cannot be dominated.
        // However, if we make all turn-restricted states strictly incomparable we can get infinite loops with
        // adjacent turn restrictions, see #88.
        // So we make an exception that states with exactly the same turn restrictions dominate one another.
        // In practice, this means once we have a state with a certain set of turn restrictions, we don't allow any
        // more at the same location.
        if (s1.turnRestrictions != null && s2.turnRestrictions != null &&
            s1.turnRestrictions.size() == s2.turnRestrictions.size()) {
                boolean[] same = new boolean[]{true}; // Trick to circumvent java "effectively final" ridiculousness.
                s1.turnRestrictions.forEachEntry((ridx, pos) -> {
                    if (!s2.turnRestrictions.containsKey(ridx) || s2.turnRestrictions.get(ridx) != pos) same[0] = false;
                    return same[0]; // Continue iteration until a difference is discovered, then bail out.
                });
                if (same[0]) return true; // s1 dominates s2 because it has the same turn restrictions.
                // TODO shouldn't we add a test to see which one has the lower dominance variable, just to make this more principled?
                // As in: states are comparable only when they have the same set of turn restrictions.
        }
        // At least one of the states has turn restrictions. Neither dominates the other.
        return false;
    }

    /**
     * Get a single best state at the end of an edge.
     * There can be more than one state at the end of an edge due to turn restrictions
     */
    public State getStateAtEdge (int edgeIndex) {
        Collection<State> states = bestStatesAtEdge.get(edgeIndex);
        if (states.isEmpty()) {
            return null; // Unreachable
        }
        // Get the lowest weight, even if it's in the middle of a turn restriction.
        return states.stream().reduce((s0, s1) ->
                s0.getRoutingVariable(quantityToMinimize) < s1.getRoutingVariable(quantityToMinimize) ? s0 : s1).get();
    }

    /**
     * Get a single best state at a vertex. NB this should not be used for propagating to samples, as you need to apply
     * turn costs/restrictions during propagation.
     */
    public State getStateAtVertex (int vertexIndex) {
        State ret = null;

        TIntList edgeList;
        if (profileRequest.reverseSearch) {
            edgeList = streetLayer.outgoingEdges.get(vertexIndex);
        } else {
            edgeList = streetLayer.incomingEdges.get(vertexIndex);
        }

        for (TIntIterator it = edgeList.iterator(); it.hasNext();) {
            int eidx = it.next();

            State state = getStateAtEdge(eidx);

            if (state == null) continue;

            if (ret == null) ret = state;
            else if (ret.getRoutingVariable(quantityToMinimize) > state.getRoutingVariable(quantityToMinimize)) {
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
     * Given a _destination_ split along an edge, return the best state that can be produced by traversing the edge
     * fragments from the vertices at either end of the edge up to the destination split point.
     * If no states can be produced return null.
     *
     * Note that this is only used by the point to point street router, not by LinkedPointSets (which have equivalent
     * logic in their eval method). The PointSet implementation only needs to produce times, not States. But ideally
     * some common logic can be factored out.
     */
    public State getState (Split split) {
        List<State> candidateStates = new ArrayList<>();

        // Start on the forward edge of the pair that was split
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor(split.edge);

        TIntList edgeList;
        if (profileRequest.reverseSearch) {
            edgeList = streetLayer.outgoingEdges.get(split.vertex1);
        } else {
            edgeList = streetLayer.incomingEdges.get(split.vertex0);
        }
        // TODO change iteration style to imperative
        for (TIntIterator it = edgeList.iterator(); it.hasNext();) {
            Collection<State> states = bestStatesAtEdge.get(it.next());
            // NB this needs a state to copy turn restrictions into. We then don't use that state, which is fine because
            // we don't need the turn restrictions any more because we're at the end of the search
            states.stream().filter(s -> e.canTurnFrom(s, new State(-1, split.edge, s), profileRequest.reverseSearch))
                    .map(s -> {
                        State ret = new State(-1, split.edge, s);
                        ret.streetMode = s.streetMode;

                        // figure out the turn cost
                        int turnCost = this.timeCalculator.turnTimeSeconds(s.backEdge, split.edge, s.streetMode);
                        int traversalCost = (int) Math.round(split.distance0_mm / 1000d / e.calculateSpeed(profileRequest, s.streetMode));

                        // TODO length of perpendicular
                        ret.incrementTimeInSeconds(turnCost + traversalCost);
                        ret.distance += split.distance0_mm;

                        return ret;
                    })
                    .forEach(candidateStates::add);
        }

        // Advance to the backward edge of the pair that was split
        e.advance();

        if (profileRequest.reverseSearch) {
            edgeList = streetLayer.outgoingEdges.get(split.vertex0);
        } else {
            edgeList = streetLayer.incomingEdges.get(split.vertex1);
        }

        for (TIntIterator it = edgeList.iterator(); it.hasNext();) {
            Collection<State> states = bestStatesAtEdge.get(it.next());
            for (State state : states) {
                if (!e.canTurnFrom(state, new State(-1, split.edge + 1, state), profileRequest.reverseSearch)) {
                    continue;
                }
                State ret = new State(-1, split.edge + 1, state);
                ret.streetMode = state.streetMode;
                int turnCost = this.timeCalculator.turnTimeSeconds(state.backEdge, split.edge + 1, state.streetMode);
                int traversalCost = (int) Math.round(split.distance1_mm / 1000d / e.calculateSpeed(profileRequest, state.streetMode));
                ret.distance += split.distance1_mm;
                // TODO length of perpendicular
                ret.incrementTimeInSeconds(turnCost + traversalCost);
                candidateStates.add(ret);
            }
        }

        return candidateStates.stream()
                .reduce((s0, s1) -> s0.getRoutingVariable(quantityToMinimize) < s1.getRoutingVariable(quantityToMinimize) ? s0 : s1)
                .orElse(null);
    }

    public Split getDestinationSplit() {
        return destinationSplit;
    }

    public Split getOriginSplit() { return originSplit; }

    /**
     * Given the geographic coordinates of a starting point...
     * Returns the State with the smaller weight to vertex0 or vertex1
     * TODO explain what this is for.
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
        // FIXME this method being called is intended for destinations not origins!
        //  Fortunately this is only being called in PointToPointQuery which we are not using.
        return getState(split);
    }

    public static class State implements Cloneable,Serializable {
        public int vertex;
        public int backEdge;

        //In simple search both those variables have same values
        //But in complex search (P+R, Bike share) first variable have duration of all the legs
        //and second, duration only in this leg
        //this is used for limiting search time in VertexFlagVisitor
        protected int durationSeconds;
        protected int durationFromOriginSeconds;
        //Distance in mm
        public int distance;
        public int idx;
        public StreetMode streetMode;
        public State backState; // previous state in the path chain
        public boolean isBikeShare = false; //is true if vertex in this state is Bike sharing station where mode switching occurs
        public int heuristic = 0; // Lower bound on remaining weight to the destination.

        /**
         * All turn restrictions this state is currently passing through.
         * The values are how many edges of a turn restriction have been traversed so far,
         * keyed on the turn restriction index.
         * If the value is 1 we have traversed only the from edge, etc.
         */
        public TIntIntMap turnRestrictions;

        public State(int atVertex, int viaEdge, State backState) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            //Note here it can happen that back state has edge with negative index
            //This means that this state was created from vertex and can be skipped in display
            //but it is necessary in bike sharing and P+R to combine WALK and BIKE/CAR parts+
            this.backState = backState;
            this.distance = backState.distance;
            this.durationSeconds = backState.durationSeconds;
            this.durationFromOriginSeconds = backState.durationFromOriginSeconds;
            this.idx = backState.idx+1;
        }

        public State(int atVertex, int viaEdge, StreetMode streetMode) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            this.backState = null;
            this.distance = 0;
            this.streetMode = streetMode;
            this.durationSeconds = 0;
            this.durationFromOriginSeconds = 0;
            this.idx = 0;
        }

        protected State clone() {
            State ret;
            try {
                ret = (State) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException("This is not happening");
            }
            return ret;
        }

        /**
         * Reverses order of states in arriveBy=true searches. Because start and target are reversed there
         * @param transportNetwork this is used for getting from/to vertex in backEdge
         * @return last edge in reversed order
         */
        public State reverse(TransportNetwork transportNetwork) {
            State orig = this;
            State ret = orig.reversedClone();
            int edge = -1;
            while (orig.backState != null) {
                //LOG.info("START ORIG:{} RET:{}", orig, ret);
                edge = orig.backEdge;
                State child = ret.clone();
                child.backState = ret;
                child.backEdge = edge;
                boolean traversingBackward = false;
                EdgeStore.Edge origBackEdge = transportNetwork.streetLayer.edgeStore.getCursor(orig.backEdge);
                if (origBackEdge.getFromVertex() == origBackEdge.getToVertex()
                    && ret.vertex == origBackEdge.getFromVertex()) {
                    traversingBackward = true;
                    child.vertex = origBackEdge.getToVertex();
                    //LOG.info("Case 1");
                } else if (ret.vertex == origBackEdge.getFromVertex()) {
                    child.vertex = origBackEdge.getToVertex();
                    traversingBackward = false;
                    //LOG.info("Case 2");
                }else if (ret.vertex == origBackEdge.getToVertex()) {
                    child.vertex = origBackEdge.getFromVertex();
                    traversingBackward = true;
                    //LOG.info("Case 3");
                }
                //LOG.info("State idx:{} tra:{}", orig.idx, traversingBackward);
                /*
                if (traversingBackward != ret.getOptions().arriveBy) {
                    LOG.error("Actual traversal direction does not match traversal direction in TraverseOptions.");
                    //defectiveTraversal = true;
                }*/
                child.durationSeconds += orig.durationSeconds - orig.backState.durationSeconds;
                if (orig.backState != null) {
                    child.distance += Math.abs(orig.distance-orig.backState.distance);
                }
                child.streetMode = orig.streetMode;
                //LOG.info("CHILD:{}", child);
                ret = child;
                orig = orig.backState;
            }
            return ret;
        }

        public State reversedClone() {
            State newState = new State(this.vertex, -1, this.streetMode);
            newState.idx = idx;
            return newState;
        }

        public void incrementTimeInSeconds(long seconds) {
            if (seconds < 0) {
                LOG.warn("A state's time is being incremented by a negative amount while traversing edge "
                    );
                //defectiveTraversal = true;
                return;
            }
            //TODO: decrease time
            if (false) {
                durationSeconds-=seconds;
                durationFromOriginSeconds -= seconds;
            } else {
                durationSeconds+=seconds;
                durationFromOriginSeconds += seconds;
            }

        }

        public int getDurationSeconds() {
            return durationSeconds;
        }

        public String dump() {
            State state = this;
            StringBuilder out = new StringBuilder();
            out.append("BEGIN PATH DUMP\n");
            while (state != null) {
                out.append(String.format("%s via %s\n", state.vertex, state.backEdge));
                state = state.backState;
            }
            out.append("END PATH DUMP\n");

            return out.toString();
        }

        public String compactDump(boolean reverse) {
            State state = this;
            StringBuilder out = new StringBuilder();
            String middle;
            if (reverse) {
                middle = "->";
            } else {
                middle = "<-";
            }
            while (state != null) {
                out.append(String.format("%s %d ",middle, state.backEdge));
                state = state.backState;
            }
            return out.toString();
        }


        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("State{");
            sb.append("vertex=").append(vertex);
            sb.append(", backEdge=").append(backEdge);
            sb.append(", durationSeconds=").append(durationSeconds);
            sb.append(", distance=").append(distance);
            sb.append(", idx=").append(idx);
            sb.append('}');
            return sb.toString();
        }

        public int getRoutingVariable (RoutingVariable variable) {
            if (variable == null) {
                throw new NullPointerException("Routing variable is null.");
            }
            switch (variable) {
                case DURATION_SECONDS:
                    return this.durationSeconds;
                case DISTANCE_MILLIMETERS:
                    return this.distance;
                default:
                    throw new IllegalStateException("Unknown routing variable.");
            }
        }

        public enum RoutingVariable {
            /** Time, in seconds */
            DURATION_SECONDS,
            /** Distance, in millimeters */
            DISTANCE_MILLIMETERS
        }
    }

    /**
     * Saves maxStops number of transitStops that are at least minTravelTimeSeconds from start of search
     * If stop is found multiple times best states according to quantityToMinimize wins.
     */
    private static class StopVisitor implements RoutingVisitor {
        private final int minTravelTimeSeconds;

        private final StreetLayer streetLayer;

        private final State.RoutingVariable dominanceVariable;

        private final int maxStops;

        private final int NO_STOP_FOUND;

        TIntIntMap stops = new TIntIntHashMap();

        /**
         * @param streetLayer          needed because we need stopForStreetVertex
         * @param dominanceVariable    according to which dominance variable should states be compared (same as in routing)
         * @param maxStops             maximal number of stops that should be found
         * @param minTravelTimeSeconds for stops that should be still added to list of stops
         */
        public StopVisitor(StreetLayer streetLayer, State.RoutingVariable dominanceVariable,
            int maxStops, int minTravelTimeSeconds) {
            this.minTravelTimeSeconds = minTravelTimeSeconds;
            this.streetLayer = streetLayer;
            this.dominanceVariable = dominanceVariable;
            this.maxStops = maxStops;
            this.NO_STOP_FOUND = streetLayer.parentNetwork.transitLayer.stopForStreetVertex
                .getNoEntryKey();
        }

        /**
         * If vertex at current state is transit stop. It adds it to best stops
         * if it is more then minTravelTimeSeconds away and is better then existing path
         * to the same stop according to dominance variable
         */
        @Override
        public void visitVertex(State state) {
            int stop = streetLayer.parentNetwork.transitLayer.stopForStreetVertex.get(state.vertex);
            if (stop != NO_STOP_FOUND) {
                if (state.durationSeconds < minTravelTimeSeconds) {
                    return;
                }
                if (!stops.containsKey(stop) || stops.get(stop) > state
                    .getRoutingVariable(dominanceVariable)) {
                    stops.put(stop, state.getRoutingVariable(dominanceVariable));
                }
            }

        }

        /**
         * @return true when maxStops transitStops are found
         */
        public boolean shouldBreakSearch() {
            return stops.size() >= this.maxStops;
        }

        /**
         * @return found stops. Same format of returned value as in
         */
        public TIntIntMap getStops() {
            return stops;
        }
    }

    /**
     * Saves flagSearchQuantity number of vertices which have wantedFlag
     * and are at least minTravelTimeSeconds away from the origin
     * <p>
     * If vertex is found multiple times vertex with lower quantityToMinimize is saved
     */
    private static class VertexFlagVisitor implements RoutingVisitor {
        private final int minTravelTimeSeconds;

        private final State.RoutingVariable dominanceVariable;

        private final int maxVertices;

        private final VertexStore.VertexFlag wantedFlag;

        VertexStore.Vertex v;

        TIntObjectMap<State> vertices = new TIntObjectHashMap<>();

        //Save vertices which are too close so that if they appear again (with longer path to them)
        // they are also skipped 
        TIntSet skippedVertices = new TIntHashSet();

        public VertexFlagVisitor(StreetLayer streetLayer, State.RoutingVariable dominanceVariable,
            VertexStore.VertexFlag wantedFlag, int maxVertices, int minTravelTimeSeconds) {
            this.minTravelTimeSeconds = minTravelTimeSeconds;
            this.dominanceVariable = dominanceVariable;
            this.wantedFlag = wantedFlag;
            this.maxVertices = maxVertices;
            v = streetLayer.vertexStore.getCursor();
        }

        /**
         * If vertex at current state has wantedFlag it is added to vertices map
         * if it is more then minTravelTimeSeconds away and has backState and non negative vertexIdx
         * <p>
         * If vertex is found multiple times vertex with lower quantityToMinimize is saved
         *
         * @param state
         */
        @Override
        public void visitVertex(State state) {
            if (state.vertex < 0 ||
                //skips origin states for bikeShare (since in cycle search for bikeShare origin states
                //can be added to vertices otherwise since they could be traveled for minTravelTimeSeconds with different transport mode)
                state.backState == null || state.durationFromOriginSeconds < minTravelTimeSeconds ||
                skippedVertices.contains(state.vertex)
                ) {
                // Make sure that vertex to which you can come sooner then minTravelTimeSeconds won't be used
                // if a path which uses more then minTravelTimeSeconds is found
                // since this means we need to walk/cycle/drive longer then required
                if (state.vertex > 0 && state.durationFromOriginSeconds < minTravelTimeSeconds) {
                    skippedVertices.add(state.vertex);
                }
                return;
            }
            v.seek(state.vertex);
            if (v.getFlag(wantedFlag)) {
                if (!vertices.containsKey(state.vertex)
                    || vertices.get(state.vertex).getRoutingVariable(dominanceVariable) > state
                    .getRoutingVariable(dominanceVariable)) {
                    vertices.put(state.vertex, state);
                }
            }

        }

        /**
         * @return true when flagSearchQuantity vertices are found
         */
        public boolean shouldBreakSearch() {
            return vertices.size() >= this.maxVertices;
        }

        /**
         * @return found vertices with wantedFlag. Same format of returned value as in {@link StreetRouter#getReachedVertices(VertexStore.VertexFlag)}
         */
        public TIntObjectMap<State> getVertices() {
            return vertices;
        }
    }

    /**
     * Continue a search by walking (presumably after a car or bicycle search is complete).
     * This allows accessing transit stops that are linked to edges that are only walkable, but not drivable or bikeable.
     * This maintains the total travel time limit and other parameters.
     * NOTE: this conflicts with the rule that a router should not be reused.
     * This is a good example of why we may want to change that rule. Alternatively this could construct a new StreetRouter.
     * Just allowing more than one mode doesn't give the desired effect - we really want a sequence of separate modes.
     */
    public void keepRoutingOnFoot() {
        queue.clear();
        bestStatesAtEdge.forEachEntry((edgeId, states) -> queue.addAll(states));
        streetMode = StreetMode.WALK;
        route();
    }

}
