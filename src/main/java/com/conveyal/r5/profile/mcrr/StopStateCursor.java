package com.conveyal.r5.profile.mcrr;

public interface StopStateCursor {

    StopState stop(int round, int stop);

    boolean stopNotVisited(int round, int stop);
}
