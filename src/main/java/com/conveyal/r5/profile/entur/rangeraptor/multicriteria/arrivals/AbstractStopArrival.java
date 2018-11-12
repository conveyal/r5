package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.RRStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.DebugState;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoFunction;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSortable;

import java.util.LinkedList;
import java.util.List;

import static com.conveyal.r5.profile.entur.util.paretoset.ParetoFunction.createParetoFunctions;

public abstract class AbstractStopArrival<T extends TripScheduleInfo> implements RRStopArrival<T>, ParetoSortable {

    /**
     * The pareto function MUST match the {@code ParetoSortable} implementation below
     */
    public static final ParetoFunction[] PARETO_FUNCTION = createParetoFunctions()
            .lessThen()  // time
            .lessThen()  // rounds
            .lessThen()  // cost
            .build();

    private final AbstractStopArrival<T> previousArrival;
    private final int round;
    private final int stop;
    private final int time;
    private final int roundPareto;
    private final int cost;


    /**
     * Transit or transfer
     */
    AbstractStopArrival(AbstractStopArrival<T> previousArrival, int round, int roundPareto, int stop, int arrivalTime, int cost) {
        this.previousArrival = previousArrival;
        this.round = round;
        this.roundPareto = roundPareto;
        this.stop = stop;
        this.time = arrivalTime;
        this.cost = cost;
    }

    /**
     * Initial state - first stop visited.
     */
    AbstractStopArrival(int stop, int arrivalTime, int initialCost) {
        this.previousArrival = null;
        this.round = 0;
        this.roundPareto = 0;
        this.stop = stop;
        this.time = arrivalTime;
        this.cost = initialCost;
    }


    /* pareto vector, the {@code ParetoSortable} implementation */
    @Override public final int paretoValue1() { return time;        }
    @Override public final int paretoValue2() { return roundPareto; }
    @Override public final int paretoValue3() { return cost;        }

    /**
     * @return previous state or throw a NPE if no previousArrival exist.
     */
    @SuppressWarnings({"ConstantConditions"})
    final int previousStop() {
        return previousArrival.stop;
    }

    public final AbstractStopArrival<T> previousArrival() {
        return previousArrival;
    }

    /**
     * @see TransitStopArrival#originFromTime()
     */
    int originFromTime() {
        //noinspection ConstantConditions - NPE is not possible unless there is a programming error.
        return previousArrival.originFromTime();
    }

    public final int stopIndex() {
        return stop;
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

    public boolean arrivedByTransitLastRound() {
        return false;
    }

    @Override
    public T trip() {
        return null;
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

    public int cost() {
        return cost;
    }

    @Override
    public String toString() {
        return asString(getClass().getSimpleName(), round(), stop);
    }

    public void debug() {
        DebugState.debugStop(round, stop, this);
    }

    public List<AbstractStopArrival<T>> path() {
        List<AbstractStopArrival<T>> path = new LinkedList<>();
        AbstractStopArrival<T> current = this;

        path.add(current);

        while (current.previousArrival != null) {
            current = current.previousArrival;
            path.add(0, current);
        }
        return path;
    }
}
