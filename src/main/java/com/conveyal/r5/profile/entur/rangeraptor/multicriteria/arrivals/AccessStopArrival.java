package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;


/**
 * Represent a access stop arrival.
 */
public class AccessStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
    public final int accessDurationInSeconds;
    public final int boardSlackInSeconds;


    public AccessStopArrival(StopArrival stopArrival, int fromTime, int boardSlackInSeconds) {
        super(stopArrival.stop(), fromTime + stopArrival.durationInSeconds(), stopArrival.cost());
        this.accessDurationInSeconds = stopArrival.durationInSeconds();
        this.boardSlackInSeconds = boardSlackInSeconds;
    }

    public int originFromTime(int boardTime) {
        return boardTime - (accessDurationInSeconds + boardSlackInSeconds);
    }
}
