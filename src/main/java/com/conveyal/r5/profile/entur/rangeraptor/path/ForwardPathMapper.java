package com.conveyal.r5.profile.entur.rangeraptor.path;

import com.conveyal.r5.profile.entur.api.path.AccessPathLeg;
import com.conveyal.r5.profile.entur.api.path.EgressPathLeg;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.path.PathLeg;
import com.conveyal.r5.profile.entur.api.path.TransferPathLeg;
import com.conveyal.r5.profile.entur.api.path.TransitPathLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;


/**
 * Build a path from a destination arrival - this maps between the domain of routing
 * to the domain of result paths. All values not needed for routing is computed as part of this mapping.
 */
public final class ForwardPathMapper<T extends TripScheduleInfo> implements PathMapper<T> {

    @Override
    public Path<T> mapToPath(final DestinationArrivalView<T> destinationArrival) {
        StopArrivalView<T> from;
        StopArrivalView<T> to;
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

        int boardTimeTransit = transitLeg.fromTime();

        AccessPathLeg<T> accessLeg = new AccessPathLeg<T>(
                from.departureTimeAccess(boardTimeTransit),
                from.stop(),
                from.arrivalTimeAccess(boardTimeTransit),
                transitLeg
        );

        return new Path<>(
                accessLeg,
                destinationArrival.arrivalTime(),
                numberOfTransits - 1,
                destinationArrival.cost()
        );
    }
}
