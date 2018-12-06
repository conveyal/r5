package com.conveyal.r5.profile.entur.api.path;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;


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
        PathLeg<T> leg = accessLeg;
        while (!leg.isEgressLeg()) {
            leg = leg.nextLeg();
        }
        return leg.asEgressLeg();
    }
}
