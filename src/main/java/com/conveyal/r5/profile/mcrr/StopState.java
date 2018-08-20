package com.conveyal.r5.profile.mcrr;

import static com.conveyal.r5.profile.mcrr.IntUtils.intToString;
import static com.conveyal.r5.util.TimeUtils.timeToString;

public interface StopState {
    boolean DEBUG = false;

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

    default void debugStop(String descr, int round, int stop) {
        if(!DEBUG) return;

        if(round < 0 || stop < 1) {
            System.err.printf("  S %-24s  %2d %6d  STOP DOES NOT EXIST!%n", descr, round, stop);
            return;
        }

        System.err.printf("  S %-24s  %2d %6d   %s%n",
                descr,
                round,
                stop,
                asString()
        );
    }


    String[] STOP_HEADERS =  {
            "- TRANSFER FROM -   ----------- TRANSIT -----------",
            " Time   Stop  Dur    Time B.Stop B.Time  Pttrn Trip"
    };

    default String asString() {
        return String.format("%5s %6s %4s   %5s %6s %6s %6s %4s",
                timeToString(time(), UNREACHED),
                intToString(transferFromStop(), NOT_SET),
                intToString(transferTime(), NOT_SET),
                timeToString(transitTime(), UNREACHED),
                intToString(boardStop(), NOT_SET),
                timeToString(boardTime(), UNREACHED),
                intToString(previousPattern(), NOT_SET),
                intToString(previousTrip(), NOT_SET)
        );
    }
}
