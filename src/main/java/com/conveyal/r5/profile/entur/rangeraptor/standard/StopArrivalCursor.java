package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.RRStopArrival;

public interface StopArrivalCursor<T extends TripScheduleInfo> {

    RRStopArrival<T> stop(int round, int stop);

    boolean stopNotVisited(int round, int stop);
}
