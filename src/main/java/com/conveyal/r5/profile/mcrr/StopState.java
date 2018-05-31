package com.conveyal.r5.profile.mcrr;

public interface StopState {
    int time();

    int transitTime();

    boolean isTransitTimeSet();

    int previousPattern();

    int previousTrip();

    int boardStop();

    int boardTime();

    int transferFromStop();

    boolean arrivedByTransfer();

    int transferTime();
}
