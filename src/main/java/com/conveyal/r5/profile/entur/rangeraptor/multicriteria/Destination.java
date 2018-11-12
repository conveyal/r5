package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.EgressLeg;
import com.conveyal.r5.profile.entur.api.TransferLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.DestinationArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSet;


public class Destination<T extends TripScheduleInfo> extends ParetoSet<DestinationArrival<T>> {

    public Destination() {
        super(DestinationArrival.PARETO_FUNCTION);
    }

    void transferToDestination(TransitStopArrival<T> lastTransitArrival, EgressLeg egressLeg) {
        add(new DestinationArrival<>(
                lastTransitArrival,
                lastTransitArrival.transitTime() + egressLeg.durationInSeconds(),
                lastTransitArrival.round(),
                lastTransitArrival.cost() + egressLeg.cost()
        ));
    }
}
