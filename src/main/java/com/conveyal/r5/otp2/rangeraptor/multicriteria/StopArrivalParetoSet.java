package com.conveyal.r5.otp2.rangeraptor.multicriteria;

import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.ArrivalView;
import com.conveyal.r5.otp2.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.otp2.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.otp2.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.otp2.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.otp2.util.paretoset.ParetoSetEventListener;
import com.conveyal.r5.otp2.util.paretoset.ParetoSetEventListenerComposite;
import com.conveyal.r5.otp2.util.paretoset.ParetoSetWithMarker;

/**
 * A pareto optimal set of stop arrivals for a given stop.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class StopArrivalParetoSet<T extends TripScheduleInfo> extends ParetoSetWithMarker<AbstractStopArrival<T>> {

    /**
     * Use the factory methods in this class to create a new instance.
     */
    StopArrivalParetoSet(ParetoSetEventListener<ArrivalView<T>> listener) {
        super(AbstractStopArrival.compareArrivalTimeRoundAndCost(), listener);
    }

    /**
     * Create a stop arrivals pareto set and attach a debugger is handler exist.
     */
    static <T extends TripScheduleInfo> StopArrivalParetoSet<T> createStopArrivalSet(
            int stop,
            DebugHandlerFactory<T> debugHandlerFactory
    ) {
        return new StopArrivalParetoSet<>(debugHandlerFactory.paretoSetStopArrivalListener(stop));
    }

    /**
     * Create a new StopArrivalParetoSet and attach a debugger if it exist. Also
     * attach a {@link CalculateTransferToDestination} listener witch will create
     * new destination arrivals for each accepted egress stop arrival.
     */
    static <T extends TripScheduleInfo> StopArrivalParetoSet<T> createEgressStopArrivalSet(
            TransferLeg egressLeg,
            CostCalculator costCalculator,
            DestinationArrivalPaths<T> destinationArrivals,
            DebugHandlerFactory<T> debugHandlerFactory
    ) {
        ParetoSetEventListener<ArrivalView<T>> listener;
        ParetoSetEventListener<ArrivalView<T>> debugListener;

        listener = new CalculateTransferToDestination<>(egressLeg, destinationArrivals, costCalculator);
        debugListener = debugHandlerFactory.paretoSetStopArrivalListener(egressLeg.stop());

        if(debugListener != null) {
            listener = new ParetoSetEventListenerComposite<>(debugListener, listener);
        }

        return new StopArrivalParetoSet<>(listener);
    }
}
