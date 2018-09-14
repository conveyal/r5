package com.conveyal.r5.profile.entur.rangeraptor.standard;

public interface StopStateCursor {

    StopState stop(int round, int stop);

    boolean stopNotVisited(int round, int stop);
}
