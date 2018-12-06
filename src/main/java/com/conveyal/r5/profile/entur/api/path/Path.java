package com.conveyal.r5.profile.entur.api.path;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.PathStringBuilder;

import java.util.ArrayList;
import java.util.List;


/**
 * The result path of a Raptor search describing the one possible journey.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class Path<T extends TripScheduleInfo> {
    private final int startTime;
    private final int endTime;
    private final int numberOfTransfers;
    private final AccessPathLeg<T> accessLeg;
    private EgressPathLeg<T> egressPathLeg = null;

    public Path(AccessPathLeg<T> accessLeg, int endTime, int numberOfTransfers) {
        this.accessLeg = accessLeg;
        this.startTime = accessLeg.fromTime();
        this.endTime = endTime;
        this.numberOfTransfers = numberOfTransfers;
    }

    /**
     * The journey start time. The departure time from the journey origin.
     */
    public final int startTime() {
        return startTime;
    }

    /**
     * The journey end time. The arrival time at the journey destination.
     */
    public final int endTime() {
        return endTime;
    }

    /**
     * The total journey duration in seconds.
     */
    public final int totalTravelDurationInSeconds() {
        return endTime - startTime;
    }

    /**
     * The total number of transfers for this journey.
     */
    public final int numberOfTransfers() {
        return numberOfTransfers;
    }

    /**
     * The first leg of this journey - witch is linked to the next and so on.
     */
    public final AccessPathLeg<T> accessLeg() {
        return accessLeg;
    }

    /**
     * The last leg of this journey.
     */
    public final EgressPathLeg<T> egressLeg() {
        if(egressPathLeg == null) {
            PathLeg<T> leg = accessLeg;
            while (!leg.isEgressLeg()) {
                leg = leg.nextLeg();
            }
            egressPathLeg = leg.asEgressLeg();
        }
        return egressPathLeg;
    }

    /**
     * Utility method to list all visited stops.
     */
    public List<Integer> listStops() {
        List<Integer> stops = new ArrayList<>();
        PathLeg<?> leg = accessLeg.nextLeg();

        while (!leg.isEgressLeg()) {
            if (leg.isTransitLeg()) {
                stops.add(leg.asTransitLeg().fromStop());
            }
            if (leg.isTransferLeg()) {
                stops.add(leg.asTransferLeg().fromStop());
            }
            leg = leg.nextLeg();
        }
        stops.add(leg.asEgressLeg().fromStop());
        return stops;
    }

    @Override
    public String toString() {
        PathStringBuilder buf = new PathStringBuilder();
        PathLeg<T> leg = accessLeg.nextLeg();

        buf.walk(accessLeg.duration());

        while (!leg.isEgressLeg()) {
            buf.sep();
            if(leg.isTransitLeg()) {
                buf.stop(leg.asTransitLeg().fromStop()).sep().transit(leg.fromTime(), leg.toTime());
            }
            // Transfer
            else {
                buf.stop(leg.asTransferLeg().fromStop()).sep().walk(leg.duration());
            }
            leg = leg.nextLeg();
        }
        buf.sep().stop(leg.asEgressLeg().fromStop()).sep().walk(leg.duration());

        return buf.toString();
    }
}
