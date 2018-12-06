package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.EgressLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.DestinationArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSet;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class Destination<T extends TripScheduleInfo> extends ParetoSet<DestinationArrival<T>> {


    public Destination() {
        // The `travelDuration` is added as a criteria to the pareto comparator in addition to the parameters
        // used for each stop arrivals. The `travelDuration` is only needed at the destination because Range Raptor
        // works in iterations backwards in time.
        super((l, r) ->
                l.arrivalTime() < r.arrivalTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.cost() < r.cost() ||
                l.travelDuration() < r.travelDuration()
        );
    }

    void transferToDestination(TransitStopArrival<T> lastTransitArrival, EgressLeg egressLeg) {
        add(new DestinationArrival<>(
                lastTransitArrival,
                lastTransitArrival.arrivalTime() + egressLeg.durationInSeconds(),
                lastTransitArrival.round(),
                lastTransitArrival.cost() + egressLeg.cost()
        ));
    }
}
