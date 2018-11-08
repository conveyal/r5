package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;


class McAccessStopArrivalState<T extends TripScheduleInfo> extends McStopArrivalState<T> {
    final int accessDurationInSeconds;
    final int boardSlackInSeconds;


    McAccessStopArrivalState(StopArrival stopArrival, int fromTime, int boardSlackInSeconds) {
        super(stopArrival.stop(), fromTime + stopArrival.durationInSeconds(), stopArrival.cost());
        this.accessDurationInSeconds = stopArrival.durationInSeconds();
        this.boardSlackInSeconds = boardSlackInSeconds;
    }
}
