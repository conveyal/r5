package com.conveyal.r5.profile;

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

    public StreetPath(StreetRouter.State s, TransportNetwork transportNetwork) {
        edges = new LinkedList<>();
        states = new LinkedList<>();
        this.transportNetwork = transportNetwork;

        lastState = s;

        /*
         * Starting from latest (time-wise) state, copy states to the head of a list in reverse
         * chronological order. List indices will thus increase forward in time, and backEdges will
         * be chronologically 'back' relative to their state.
         */
        for (StreetRouter.State cur = s; cur != null; cur = cur.backState) {
            states.addFirst(cur);
            if (cur.backEdge != -1 && cur.backState != null) {
                edges.addFirst(cur.backEdge);
            }
        }
        firstState = states.getFirst();
    }

    public LinkedList<StreetRouter.State> getStates() {
        return states;
    }

    public LinkedList<Integer> getEdges() {
        return edges;
    }

    public int getDuration() {
        //Division with 1000 because time is returned in ms and we need seconds
        return (int) Math.abs(lastState.getTime()-firstState.getTime())/1000;
    }

    public int getDistance() {
        //FIXME: implement distance in state
        return 0;
    }

    public EdgeStore.Edge getEdge(Integer edgeIdx) {
        return transportNetwork.streetLayer.edgeStore.getCursor(edgeIdx);
    }
}
