package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.StopArrivalState;

public interface StopStateCursor<T extends TripScheduleInfo> {

    StopArrivalState<T> stop(int round, int stop);

    boolean stopNotVisited(int round, int stop);
}
