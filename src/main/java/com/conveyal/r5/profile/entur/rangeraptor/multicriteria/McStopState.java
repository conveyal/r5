package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopState;
import com.conveyal.r5.profile.entur.util.DebugState;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoDominanceFunctions;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSortable;

import java.util.LinkedList;
import java.util.List;

import static com.conveyal.r5.profile.entur.util.paretoset.ParetoDominanceFunctions.createParetoDominanceFunctionArray;

public abstract class McStopState implements StopState, ParetoSortable {
    static final ParetoDominanceFunctions.Builder PARETO_FUNCTION = createParetoDominanceFunctionArray()
            .lessThen(1)  // time - needs to be 1 seconds better to make it into the pareto set.
            .lessThen()   // rounds
            .lessThen(55) // cost, assuming meters or seconds as unit for cost
            ;

    private final McStopState previousState;
    private final int round;
    private final int stopIndex;
    private final int time;
    private final int roundPareto;
    private final int cost;


    private McStopState(McStopState previousState, int round, int roundPareto, int stopIndex, int arrivalTime, int cost) {
        this.previousState = previousState;
        this.round = round;
        this.roundPareto = roundPareto;
        this.stopIndex = stopIndex;
        this.time = arrivalTime;
        this.cost = cost;
    }

    /**
     * Initial state - first stop visited.
     */
    McStopState(StopArrival stopArrival, int arrivalTime) {
        this(null, 0, 0, stopArrival.stop(), arrivalTime, stopArrival.cost());
    }

    /**
     * Arrive by transfer.
     */
    McStopState(McStopState previousState, int round, int roundPareto, StopArrival stopArrival, int arrivalTime) {
        this(previousState, round, roundPareto, stopArrival.stop(), arrivalTime, stopArrival.cost());
    }

    /**
     * Arrive by transfer.
     */
    McStopState(McStopState previousState, int round, int roundPareto, int stopIndex, int arrivalTime) {
        this(previousState, round, roundPareto, stopIndex, arrivalTime, previousState.cost);
    }

    /* pareto vector */
    @Override public final int paretoValue1() {  return time;       }
    @Override public final int paretoValue2() { return roundPareto; }
    @Override public final int paretoValue3() { return cost;        }

    final int previousStop() {
        return previousState.stopIndex;
    }

    final McStopState previousState() {
        return previousState;
    }

    public final int stopIndex() {
        return stopIndex;
    }

    public final int round() {
        return round;
    }

    @Override
    public final int time() {
        return time;
    }

    @Override
    public int transitTime() {
        return UNREACHED;
    }

    @Override
    public boolean arrivedByTransit() {
        return false;
    }

    @Override
    public int pattern() {
        return NOT_SET;
    }

    @Override
    public int trip() {
        return NOT_SET;
    }

    @Override
    public int transferTime() {
        return NOT_SET;
    }

    @Override
    public int boardStop() {
        return NOT_SET;
    }

    @Override
    public int boardTime() {
        return UNREACHED;
    }

    @Override
    public int transferFromStop() {
        return NOT_SET;
    }

    @Override
    public boolean arrivedByTransfer() {
        return false;
    }

    @Override
    public String toString() {
        return asString(type().name(), round(), previousState == null ? -1 : previousState.stopIndex);
    }

    abstract DebugState.Type type();

    public void debug() {
        DebugState.debugStop(type(), round, stopIndex, this);
    }

    public List<McStopState> path() {
        List<McStopState> path = new LinkedList<>();
        McStopState current = this;

        path.add(current);

        while (current.previousState != null) {
            current = current.previousState;
            path.add(0, current);
        }
        return path;
    }
}
