package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.DebugState;

import static com.conveyal.r5.profile.entur.util.DebugState.Type.Access;

class McAccessStopState<T extends TripScheduleInfo> extends McStopState<T> {
    final int accessDurationInSeconds;
    final int boardSlackInSeconds;


    McAccessStopState(StopArrival stopArrival, int fromTime, int boardSlackInSeconds) {
        super(stopArrival.stop(), fromTime + stopArrival.durationInSeconds(), stopArrival.cost());
        this.accessDurationInSeconds = stopArrival.durationInSeconds();
        this.boardSlackInSeconds = boardSlackInSeconds;
    }

    @Override
    DebugState.Type type() { return Access; }

}
