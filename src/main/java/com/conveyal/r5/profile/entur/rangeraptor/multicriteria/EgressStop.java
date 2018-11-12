package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;

class EgressStop<T extends TripScheduleInfo> extends Stop<T> {

    private final StopArrival egressLeg;
    private final Destination<T> destination;

    EgressStop(StopArrival egressLeg, Destination<T> destination) {
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
