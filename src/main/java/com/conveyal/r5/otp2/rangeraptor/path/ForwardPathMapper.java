package com.conveyal.r5.otp2.rangeraptor.path;

import com.conveyal.r5.otp2.api.path.AccessPathLeg;
import com.conveyal.r5.otp2.api.path.EgressPathLeg;
import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.path.PathLeg;
import com.conveyal.r5.otp2.api.path.TransferPathLeg;
import com.conveyal.r5.otp2.api.path.TransitPathLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.ArrivalView;
import com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator;


/**
 * Build a path from a destination arrival - this maps between the domain of routing
 * to the domain of result paths. All values not needed for routing is computed as part of this mapping.
 */
public final class ForwardPathMapper<T extends TripScheduleInfo> implements PathMapper<T> {
    private final TransitCalculator calculator;

    public ForwardPathMapper(TransitCalculator calculator) {
        this.calculator = calculator;
    }

    @Override
    public Path<T> mapToPath(final DestinationArrival<T> destinationArrival) {
        ArrivalView<T> from;
        ArrivalView<T> to;
        PathLeg<T> lastLeg;
        TransitPathLeg<T> transitLeg;
        int numberOfTransits = 0;

        from = destinationArrival.previous();
        lastLeg = new EgressPathLeg<>(
                from.stop(),
                destinationArrival.departureTime(),
                destinationArrival.arrivalTime()
        );

        do {
            to = from;
            from = from.previous();
            ++numberOfTransits;

            transitLeg = new TransitPathLeg<>(
                    from.stop(),
                    to.departureTime(),
                    to.stop(),
                    to.arrivalTime(),
                    to.trip(),
                    lastLeg
            );

            if (from.arrivedByTransfer()) {
                to = from;
                from = from.previous();

                lastLeg = new TransferPathLeg<>(
                        from.stop(),
                        to.departureTime(),
                        to.stop(),
                        to.arrivalTime(),
                        transitLeg
                );
            } else {
                lastLeg = transitLeg;
            }
        }
        while (from.arrivedByTransit());

        AccessPathLeg<T> accessLeg = createAccessPathLeg(from, transitLeg);

        return new Path<>(
                accessLeg,
                destinationArrival.arrivalTime(),
                numberOfTransits - 1,
                destinationArrival.cost()
        );
    }

    private AccessPathLeg<T> createAccessPathLeg(ArrivalView<T> from, TransitPathLeg<T> transitLeg) {
        int boardTimeTransit = transitLeg.fromTime();
        int accessDurationInSeconds = from.arrivalTime() - from.departureTime();
        int originDepartureTime = calculator.originDepartureTime(boardTimeTransit, accessDurationInSeconds);
        int accessArrivalTime = originDepartureTime + accessDurationInSeconds;

        return new AccessPathLeg<>(originDepartureTime, from.stop(), accessArrivalTime, transitLeg);
    }
}
