package com.conveyal.r5.profile.mcrr;

public interface StopState {
    /**
     * This value essentially serves as Infinity for ints - it's bigger than every other number.
     * It is the travel time to a transit stop or a target before that stop or target is ever reached.
     * Be careful when propagating travel times from stops to targets, adding something to UNREACHED will cause overflow.
     */
    int UNREACHED = Integer.MAX_VALUE;

    /**
     * Used to initialize all none time based attribues.
     */
    int NOT_SET = -1;

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
