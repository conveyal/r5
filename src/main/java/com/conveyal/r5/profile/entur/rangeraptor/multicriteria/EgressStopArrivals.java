package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class EgressStopArrivals<T extends TripScheduleInfo> extends StopArrivals<T> {

    private final TransferLeg egressLeg;
    private final Destination<T> destination;
    private final CostCalculator costCalculator;

    EgressStopArrivals(
            TransferLeg egressLeg,
            Destination<T> destination,
            CostCalculator costCalculator,
            DebugHandler<StopArrivalView<T>> debugHandler
    ) {
        super(egressLeg.stop(), debugHandler);
        this.egressLeg = egressLeg;
        this.destination = destination;
        this.costCalculator = costCalculator;
    }

    @Override
    public boolean add(AbstractStopArrival<T> arrival) {
        if(!arrival.arrivedByTransit()) {
            return super.add(arrival);
        }
        if(super.add(arrival)) {
            destination.transferToDestination(
                    (TransitStopArrival<T>) arrival,
                    egressLeg,
                    costCalculator.walkCost(egressLeg.durationInSeconds())
            );
            return true;
        }
        return false;
    }
}
