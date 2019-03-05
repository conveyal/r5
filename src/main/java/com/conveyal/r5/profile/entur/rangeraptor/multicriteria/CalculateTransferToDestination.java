package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSetEventListener;

import java.util.Collection;

/**
 * This class listen to egress stop arrivals and on accepted arrivals
 * make the transfer to the destination.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class CalculateTransferToDestination<T extends TripScheduleInfo>
        implements ParetoSetEventListener<StopArrivalView<T>> {

    private final TransferLeg egressLeg;
    private final DestinationArrivals<T> destinationArrivals;
    private final CostCalculator costCalculator;

    CalculateTransferToDestination(TransferLeg egressLeg, DestinationArrivals<T> destinationArrivals, CostCalculator costCalculator) {
        this.egressLeg = egressLeg;
        this.destinationArrivals = destinationArrivals;
        this.costCalculator = costCalculator;
    }

    /**
     * When a stop arrival is accepted and we arrived by transit, then add a new destination arrival.
     * <p/>
     * We do not have to handle other events like dropped or rejected.
     *
     * @param newElement the new transit arrival
     */
    @Override
    public void notifyElementAccepted(StopArrivalView<T> newElement, Collection<? extends StopArrivalView<T>> ignore) {
        if(newElement instanceof TransitStopArrival) {
            TransitStopArrival<T> transitStopArrival = (TransitStopArrival<T>) newElement;
            destinationArrivals.transferToDestination(
                    transitStopArrival,
                    egressLeg,
                    costCalculator.walkCost(egressLeg.durationInSeconds())
            );
        }
    }
}
