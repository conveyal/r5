package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;


import com.conveyal.r5.profile.entur.api.AccessLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;


/**
 * Represent a access stop arrival.
 */
public class AccessStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
    public final int accessDurationInSeconds;
    public final int boardSlackInSeconds;


    public AccessStopArrival(AccessLeg accessLeg, int fromTime, int boardSlackInSeconds) {
        super(accessLeg.stop(), fromTime + accessLeg.durationInSeconds(), accessLeg.cost());
        this.accessDurationInSeconds = accessLeg.durationInSeconds();
        this.boardSlackInSeconds = boardSlackInSeconds;
    }

    public int originFromTime(int boardTime) {
        return boardTime - (accessDurationInSeconds + boardSlackInSeconds);
    }
}
