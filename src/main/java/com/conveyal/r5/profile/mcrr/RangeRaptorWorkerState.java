package com.conveyal.r5.profile.mcrr;


/**
 * This interface define the state role needed for the Range Raptor Worker
 * to perform the RR algorithm, while not knowing anything about how the
 * data structure look like.
 *
 * We will use this to implement MultiCriteria state, and experiment with
 * diffrent memory layout strategies.
 *
 * TODO Change this role to allow it to be used in a MultiCriteria senario.
 * TODO To do so we need to, return StopArrivalEvents (stop, arrival time, and ref to transit or transfer), not
 * TODO just a set of stops. Keeping both {@link #stopsTouchedByTransitCurrentRoundIterator()} and
 * TODO {@link #bestStopsTouchedLastRoundIterator()} is probebly not nessessary, because we allways alter beetween
 * TODO these.
 */
public interface RangeRaptorWorkerState {

    void initNewDepatureForMinute(int nextMinuteDepartureTime);

    void setInitialTime(int stop, int time);

    boolean isNewRoundAvailable();

    void gotoNextRound();

    void transitToStop(int stop, int alightTime, int pattern, int trip, int boardStop, int boardTime);

    void transferToStop(int toStop, int arrivalTime, int fromStop, int transferTimeInSeconds);

    int bestTransitTime(int stop);

    boolean isStopReachedInLastRound(int stop);

    int getPatternIndexForPreviousRound(int stop);

    int bestTimePreviousRound(int stop);

    BitSetIterator stopsTouchedByTransitCurrentRoundIterator();

    BitSetIterator bestStopsTouchedLastRoundIterator();

    int getMaxRound();

    boolean isStopReachedByTransit(int stop);
}
