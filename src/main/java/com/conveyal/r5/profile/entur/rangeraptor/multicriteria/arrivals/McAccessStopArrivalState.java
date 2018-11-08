package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;


public class McAccessStopArrivalState<T extends TripScheduleInfo> extends McStopArrivalState<T> {
    public final int accessDurationInSeconds;
    public final int boardSlackInSeconds;


    public McAccessStopArrivalState(StopArrival stopArrival, int fromTime, int boardSlackInSeconds) {
        super(stopArrival.stop(), fromTime + stopArrival.durationInSeconds(), stopArrival.cost());
        this.accessDurationInSeconds = stopArrival.durationInSeconds();
        this.boardSlackInSeconds = boardSlackInSeconds;
    }
}
