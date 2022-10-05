package com.conveyal.r5.streets;

import com.conveyal.modes.StreetMode;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

public class RoutingState implements Cloneable, Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(RoutingState.class);

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
    public RoutingState backState; // previous state in the path chain
    public boolean isBikeShare = false; //is true if vertex in this state is Bike sharing station where mode switching occurs
    public int heuristic = 0; // Lower bound on remaining weight to the destination.

    /**
     * All turn restrictions this state is currently passing through.
     * The values are how many edges of a turn restriction have been traversed so far,
     * keyed on the turn restriction index.
     * If the value is 1 we have traversed only the from edge, etc.
     */
    public TIntIntMap turnRestrictions;

    public RoutingState(int atVertex, int viaEdge, RoutingState backState) {
        this.vertex = atVertex;
        this.backEdge = viaEdge;
        //Note here it can happen that back state has edge with negative index
        //This means that this state was created from vertex and can be skipped in display
        //but it is necessary in bike sharing and P+R to combine WALK and BIKE/CAR parts+
        this.backState = backState;
        this.distance = backState.distance;
        this.durationSeconds = backState.durationSeconds;
        this.durationFromOriginSeconds = backState.durationFromOriginSeconds;
        this.idx = backState.idx + 1;
    }

    public RoutingState(int atVertex, int viaEdge, StreetMode streetMode) {
        this.vertex = atVertex;
        this.backEdge = viaEdge;
        this.backState = null;
        this.distance = 0;
        this.streetMode = streetMode;
        this.durationSeconds = 0;
        this.durationFromOriginSeconds = 0;
        this.idx = 0;
    }

    public RoutingState clone() {
        RoutingState ret;
        try {
            ret = (RoutingState) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("This is not happening");
        }
        return ret;
    }

    /**
     * Reverses order of states in arriveBy=true searches. Because start and target are reversed there
     *
     * @param edgeStore this is used for getting from/to vertex in backEdge
     * @return last edge in reversed order
     */
    public RoutingState reverse(EdgeStore edgeStore) {
        RoutingState orig = this;
        RoutingState ret = orig.reversedClone();
        int edge = -1;
        while (orig.backState != null) {
            edge = orig.backEdge;
            RoutingState child = ret.clone();
            child.backState = ret;
            child.backEdge = edge;
            Edge origBackEdge = new Edge(edgeStore, orig.backEdge);
            if (origBackEdge.getFromVertex() == origBackEdge.getToVertex()
                    && ret.vertex == origBackEdge.getFromVertex()) {
                child.vertex = origBackEdge.getToVertex();
            } else if (ret.vertex == origBackEdge.getFromVertex()) {
                child.vertex = origBackEdge.getToVertex();
            } else if (ret.vertex == origBackEdge.getToVertex()) {
                child.vertex = origBackEdge.getFromVertex();
            }

            child.durationSeconds += orig.durationSeconds - orig.backState.durationSeconds;
            if (orig.backState != null) {
                child.distance += Math.abs(orig.distance - orig.backState.distance);
            }
            child.streetMode = orig.streetMode;
            ret = child;
            orig = orig.backState;
        }
        return ret;
    }

    public RoutingState reversedClone() {
        RoutingState newState = new RoutingState(this.vertex, -1, this.streetMode);
        newState.idx = idx;
        return newState;
    }

    public void incrementTimeInSeconds(long seconds) {
        if (seconds < 0) {
            LOG.warn("A state's time is being incremented by a negative amount while traversing edge ");
            return;
        }
        //TODO: decrease time
        durationSeconds += seconds;
        durationFromOriginSeconds += seconds;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public String dump() {
        RoutingState state = this;
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
        RoutingState state = this;
        StringBuilder out = new StringBuilder();
        String middle;
        if (reverse) {
            middle = "->";
        } else {
            middle = "<-";
        }
        while (state != null) {
            out.append(String.format("%s %d ", middle, state.backEdge));
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

    public int getRoutingVariable(RoutingVariable variable) {
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
}
