package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.EgressLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class EgressStop<T extends TripScheduleInfo> extends Stop<T> {

    private final EgressLeg egressLeg;
    private final Destination<T> destination;

    EgressStop(EgressLeg egressLeg, Destination<T> destination, DebugHandler<StopArrivalView<T>> debugHandler) {
        super(egressLeg.stop(), debugHandler);
        this.egressLeg = egressLeg;
        this.destination = destination;
    }

    @Override
    public boolean add(AbstractStopArrival<T> arrival) {
        if(!arrival.arrivedByTransit()) {
            return super.add(arrival);
        }
        if(super.add(arrival)) {
            destination.transferToDestination(
                    (TransitStopArrival<T>) arrival,
                    egressLeg
            );
            return true;
        }
        return false;
    }
}
