package com.conveyal.r5.profile.entur.rangeraptor;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.IntUtils;
import com.conveyal.r5.profile.entur.util.TimeUtils;


public interface StopState<T extends TripScheduleInfo> {

    enum Type { Access, Transfer, Transit }

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

    boolean arrivedByTransit();

    T trip();

    int boardStop();

    int boardTime();

    int transferFromStop();

    boolean arrivedByTransfer();

    int transferTime();

    default String asString(String description, int round, int toStop) {
        return String.format("State %s { rnd: %d, stop: %s - %s, alight: %s, board: %s, trp: %s, transfer: %s }",
                description,
                round,
                IntUtils.intToString(arrivedByTransit() ? boardStop() : transferFromStop(), NOT_SET),
                IntUtils.intToString(toStop, NOT_SET),
                TimeUtils.timeToStrLong(time(), UNREACHED),
                TimeUtils.timeToStrLong(boardTime(), UNREACHED),
                trip()==null ? "-" : trip().debugInfo(),
                TimeUtils.timeToStrCompact(transferTime(), NOT_SET)
        );
    }
}
