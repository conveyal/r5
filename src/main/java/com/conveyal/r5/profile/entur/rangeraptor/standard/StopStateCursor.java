package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.StopState;

public interface StopStateCursor<T extends TripScheduleInfo> {

    StopState<T> stop(int round, int stop);

    boolean stopNotVisited(int round, int stop);
}
