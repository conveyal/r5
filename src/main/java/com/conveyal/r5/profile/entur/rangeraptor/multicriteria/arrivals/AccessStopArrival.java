package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;


public class AccessStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
    public final int accessDurationInSeconds;
    public final int boardSlackInSeconds;


    public AccessStopArrival(StopArrival stopArrival, int fromTime, int boardSlackInSeconds) {
        super(stopArrival.stop(), fromTime + stopArrival.durationInSeconds(), stopArrival.cost());
        this.accessDurationInSeconds = stopArrival.durationInSeconds();
        this.boardSlackInSeconds = boardSlackInSeconds;
    }
}
