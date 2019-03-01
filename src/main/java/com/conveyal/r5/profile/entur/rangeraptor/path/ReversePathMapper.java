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
 * <p/>
 * This mapper maps the result of a reverse-search. And is therefor responsible for doing some adjustments
 * to the result. The internal state stores 'latest possible arrivaltimes', so to map back to paths the
 * 'board slack' is removed from the 'latest possible arrivaltime' to get next legs 'boardTime'. Also,
 * the path is reversed again, so the original origin - temporally made destination - is returned to origin
 * again ;-)
 * <p/>
 * This mapper uses recursion to reverse the results.
 */
public final class ReversePathMapper<T extends TripScheduleInfo> implements PathMapper<T> {

    private final int boardSlackInSeconds;

    public ReversePathMapper(int boardSlackInSeconds) {
        this.boardSlackInSeconds = boardSlackInSeconds;
    }

    @Override
    public Path<T> mapToPath(final DestinationArrivalView<T> to) {
        StopArrivalView<T> from;
        AccessPathLeg<T> accessLeg;

        // The path is in reverse - .
        from = to.previous();

        accessLeg = new AccessPathLeg<>(
                to.arrivalTime(),
                from.stop(),
                to.departureTime(),
                mapToTransit(from)   // Recursive
        );

        int numberOfTransits = 0;

        PathLeg<T> leg = accessLeg;

        while (!leg.isEgressLeg()) {
            numberOfTransits += leg.isTransitLeg() ? 1 : 0;
            leg = leg.nextLeg();
        }
        return new Path<>(
                accessLeg,
                leg.toTime(),
                numberOfTransits - 1,
                to.cost()
        );
    }

    private PathLeg<T> mapToPathLeg(StopArrivalView<T> to, int transitBoardTime) {
        if (to.arrivedByTransfer()) {
            return mapToTransfer(to);
        }
        else if(to.arrivedByTransit()) {
            return mapToTransit(to);
        }
        else {
            return mapToEgressLeg(to, transitBoardTime);
        }
    }

    private TransitPathLeg<T> mapToTransit(StopArrivalView<T> to) {
        StopArrivalView<T> from = to.previous();

        return new TransitPathLeg<>(
                to.stop(),
                removeBoardSlack(to.arrivalTime()),
                from.stop(),
                to.departureTime(),
                to.trip(),
                mapToPathLeg(from, to.departureTime())
        );
    }

    private int removeBoardSlack(int arrivalTime) {
        return arrivalTime + boardSlackInSeconds;
    }

    private TransferPathLeg<T> mapToTransfer(StopArrivalView<T> to) {
        StopArrivalView<T> from = to.previous();

        return new TransferPathLeg<T>(
                to.stop(),
                to.arrivalTime(),
                from.stop(),
                to.departureTime(),
                mapToTransit(from)
        );
    }

    private EgressPathLeg<T> mapToEgressLeg(StopArrivalView<T> to, int transitBoardTime) {
        return new EgressPathLeg<>(
                to.stop(),
                to.arrivalTimeAccess(transitBoardTime),
                to.departureTimeAccess(transitBoardTime)
        );
    }
}
