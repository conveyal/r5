package com.conveyal.r5.otp2.rangeraptor.multicriteria;

import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.ArrivalView;
import com.conveyal.r5.otp2.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.otp2.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.otp2.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.otp2.util.paretoset.ParetoSetEventListener;

/**
 * This class listen to pareto set egress stop arrivals and on accepted
 * transit arrivals make the transfer to the destination.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class CalculateTransferToDestination<T extends TripScheduleInfo>
        implements ParetoSetEventListener<ArrivalView<T>> {

    private final TransferLeg egressLeg;
    private final DestinationArrivalPaths<T> destinationArrivals;
    private final CostCalculator costCalculator;

    CalculateTransferToDestination(
            TransferLeg egressLeg,
            DestinationArrivalPaths<T> destinationArrivals,
            CostCalculator costCalculator
    ) {
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
    public void notifyElementAccepted(ArrivalView<T> newElement) {
        if(newElement instanceof TransitStopArrival) {
            TransitStopArrival<T> transitStopArrival = (TransitStopArrival<T>) newElement;
            destinationArrivals.add(
                    transitStopArrival,
                    egressLeg,
                    costCalculator.walkCost(egressLeg.durationInSeconds())
            );
        }
    }
}
