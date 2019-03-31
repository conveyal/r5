package com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.path;

import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.ArrivalView;
import com.conveyal.r5.otp2.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.otp2.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.otp2.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.otp2.rangeraptor.standard.ArrivedAtDestinationCheck;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.EgressStopArrivalState;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.view.StopsCursor;
import com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.otp2.rangeraptor.view.DebugHandler;
import com.conveyal.r5.otp2.util.TimeUtils;


/**
 * The responsibility of this class is to listen for egress stop arrivals and forward these as
 * Destination arrivals to the {@link DestinationArrivalPaths}.
 * <p/>
 * Range Raptor requires paths to be collected at the end of each iteration. Following
 * iterations may overwrite the existing state; Hence invalidate trips explored in previous
 * iterations. Because adding new destination arrivals to the set of paths is expensive,
 * this class optimize this by only adding new destination arrivals at the end of each round.
 * <p/>
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class EgressArrivalToPathAdapter<T extends TripScheduleInfo> implements ArrivedAtDestinationCheck {
    private final DestinationArrivalPaths<T> paths;
    private final TransitCalculator calculator;
    private final StopsCursor<T> cursor;
    private final DebugHandler<ArrivalView<T>> debugHandler;

    private boolean newElementSet;
    private EgressStopArrivalState<T> bestEgressStopArrival = null;
    private int bestDestinationTime = -1;

    public EgressArrivalToPathAdapter(
            DestinationArrivalPaths<T> paths,
            TransitCalculator calculator,
            StopsCursor<T> cursor,
            WorkerLifeCycle lifeCycle,
            DebugHandlerFactory<T> debugHandlerFactory

    ) {
        this.paths = paths;
        this.calculator = calculator;
        this.cursor = cursor;
        lifeCycle.onSetupIteration((ignore) -> setupIteration());
        lifeCycle.onRoundComplete((ignore) -> roundComplete());

        debugHandler = debugHandlerFactory.debugStopArrival();
    }

    public void add(EgressStopArrivalState<T> egressStopArrival) {
        int time = destinationArrivalTime(egressStopArrival);
        if (calculator.isBest(time, bestDestinationTime)) {
            newElementSet = true;
            bestDestinationTime = time;
            bestEgressStopArrival = egressStopArrival;
        } else {
            if (debugHandler != null) {
                debugHandler.reject(
                        cursor.stop(egressStopArrival.round(), egressStopArrival.stop()),
                        cursor.stop(bestEgressStopArrival.round(), bestEgressStopArrival.stop()),
                        "A better destination arrival time for the current iteration exist: "
                                + TimeUtils.timeToStrLong(bestDestinationTime)
                );
            }
        }
    }

    private void setupIteration() {
        newElementSet = false;
        bestEgressStopArrival = null;
        bestDestinationTime = calculator.unreachedTime();
    }

    private void roundComplete() {
        if (newElementSet) {
            addToPath(bestEgressStopArrival);
            newElementSet = false;
        }
    }

    private int destinationArrivalTime(EgressStopArrivalState<T> arrival) {
        return calculator.plusDuration(arrival.transitTime(), arrival.egressLeg().durationInSeconds());
    }

    @Override
    public boolean arrivedAtDestinationCurrentRound() {
        return newElementSet;
    }

    private void addToPath(final EgressStopArrivalState<T> it) {
        paths.add(cursor.transit(it.round(), it.stop()), it.egressLeg(), 0);
    }
}
