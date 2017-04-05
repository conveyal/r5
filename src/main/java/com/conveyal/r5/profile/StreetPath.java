package com.conveyal.r5.profile;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

/**
 * Unwinds states from last to first into a list so they can be accessed more easily
 *
 * Created by mabu on 24.12.2015.
 */
public class StreetPath {
    private static final Logger LOG = LoggerFactory.getLogger(StreetPath.class);

    private LinkedList<StreetRouter.State> states;

    private LinkedList<Integer> edges;
    private StreetRouter.State lastState;
    private StreetRouter.State firstState;
    private TransportNetwork transportNetwork;
    private int distance;
    private int duration;

    public StreetPath(StreetRouter.State s, TransportNetwork transportNetwork,
        boolean reverseSearch) {
        edges = new LinkedList<>();
        states = new LinkedList<>();
        this.transportNetwork = transportNetwork;

        lastState = s;

        if (reverseSearch) {
            this.lastState = s.reverse(transportNetwork);
        }

        /*
         * Starting from latest (time-wise) state, copy states to the head of a list in reverse
         * chronological order. List indices will thus increase forward in time, and backEdges will
         * be chronologically 'back' relative to their state.
         */
        for (StreetRouter.State cur = lastState; cur != null; cur = cur.backState) {
            states.addFirst(cur);
            if (cur.backEdge != -1) {
                edges.addFirst(cur.backEdge);
            }
        }
        firstState = states.getFirst();
        distance = lastState.distance;
        duration = lastState.getDurationSeconds();
    }

    /**
     * Creates Streetpaths which consists of multiple parts
     *
     * Like in Bike sharing and P+R
     * @param lastState last state of last streetrouter (walking from bike station or from P+R parking) this is destination
     * @param streetRouter last streetRouter (previus routers are read from previous variable)
     * @param mode BICYCLE_RENT is the only supported currently
     * @param transportNetwork
     */
    public StreetPath(StreetRouter.State lastState, StreetRouter streetRouter, LegMode mode,
        TransportNetwork transportNetwork) {
        this(lastState, transportNetwork, false);
        //First streetPath is part of path from last bicycle station to the end destination on foot
        if (mode == LegMode.BICYCLE_RENT) {
            StreetRouter.State endCycling = getStates().getFirst();
            StreetRouter bicycle = streetRouter.previousRouter;
            lastState = bicycle.getStateAtVertex(endCycling.vertex);
            if (lastState != null) {
                lastState.isBikeShare = endCycling.isBikeShare;
                //Here part from first bikeshare to the last bikeshare on rented bike is created
                add(lastState, false);
                StreetRouter first = bicycle.previousRouter;
                StreetRouter.State startCycling = getStates().getFirst();
                lastState = first.getStateAtVertex(startCycling.vertex);
                if (lastState != null) {
                    lastState.isBikeShare = startCycling.isBikeShare;
                    add(lastState, false);
                } else {
                    LOG.warn("Start to cycle path missing");
                }
            } else {
                LOG.warn("Cycle to cycle path not found");
            }
        } else if(mode == LegMode.CAR_PARK) {
             //First state in walk part of CAR PARK is state where we ended driving
            StreetRouter.State carPark = getStates().getFirst();
            //So we need to search for driving part in previous streetRouter
            StreetRouter.State carState = streetRouter.previousRouter.getStateAtVertex(carPark.vertex);
            if (carState != null) {
                add(carState, true);
            } else {
                LOG.warn("Missing CAR part of CAR_PARK trip in streetRouter!");
            }
        } else {
            LOG.error("Unknown Mode in streetpath:{}", mode);
            throw new RuntimeException("Unknown mode!");
        }
    }

    public LinkedList<StreetRouter.State> getStates() {
        return states;
    }

    public LinkedList<Integer> getEdges() {
        return edges;
    }

    public int getDuration() {
        return duration;
    }

    //Gets distance in mm
    public int getDistance() {
        return distance;
    }

    public EdgeStore.Edge getEdge(Integer edgeIdx) {
        return transportNetwork.streetLayer.edgeStore.getCursor(edgeIdx);
    }

    /**
     * Adds streetpath of this state to existing
     *
     * it adds all the new states before existing ones. Since path is reconstructed from end to start
     * @param lastState
     * @param updateDistanceTime
     */
    public void add(StreetRouter.State lastState, boolean updateDistanceTime) {
        boolean first = true;
        /*
         * Starting from latest (time-wise) state, copy states to the head of a list in reverse
         * chronological order. List indices will thus increase forward in time, and backEdges will
         * be chronologically 'back' relative to their state.
         */
        for (StreetRouter.State cur = lastState; cur != null; cur = cur.backState) {
            //Skips duplicated state in multi searches P+R and B+R since both walk/cycle and car/walk
            //have same state as stop state and start state of next search
            if (first && firstState.vertex == cur.vertex) {
                states.removeFirst();
                //First edge needs to be removed in bikeshare but not in PR search
                //Basically it needs to be removed if it is the same as current since mode changes here.
                //And even if we go in and out on the same way edge ids are different because of different direction.
                if (edges.getFirst() == cur.backEdge) {
                    edges.removeFirst();
                }
            }
            first = false;
            states.addFirst(cur);
            if (cur.backEdge != -1) {
                edges.addFirst(cur.backEdge);
            }
        }
        firstState = states.getFirst();
        if (updateDistanceTime) {
            LOG.debug("Will add {}m to {}m = {}m", lastState.distance / 1000, distance / 1000,
                (distance + lastState.distance) / 1000);
            distance += lastState.distance;
            duration += lastState.getDurationSeconds()+ PointToPointQuery.CAR_PARK_DROPOFF_TIME_S;
        }
    }
}
