package com.conveyal.r5.profile.entur.rangeraptor.standard.intarray;


import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopStateCollection;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopStateCursor;

import static com.conveyal.r5.profile.entur.rangeraptor.standard.StopState.NOT_SET;
import static com.conveyal.r5.profile.entur.rangeraptor.standard.StopState.UNREACHED;
import static com.conveyal.r5.profile.entur.util.IntUtils.newIntArray;

public final class StopStatesIntArray implements StopStateCollection {
    private int size = 0;

    private final int[][] stateStopIndex;

    private final int[] times;
    private final int[] transitTimes;
    private final int[] previousPatterns;
    private final int[] previousTrips;
    private final int[] boardTimes;
    private final int[] transferTimes;
    private final int[] boardStops;
    private final int[] transferFromStops;


    public StopStatesIntArray(int rounds, int stops) {
        this.stateStopIndex = new int[rounds][stops];

        final int limit = 3 * stops;

        this.times = newIntArray(limit, UNREACHED);

        this.boardStops = newIntArray(limit, NOT_SET);
        this.transitTimes = newIntArray(limit, UNREACHED);
        this.previousPatterns = newIntArray(limit, NOT_SET);
        this.previousTrips = newIntArray(limit, NOT_SET);
        this.boardTimes = newIntArray(limit, UNREACHED);

        this.transferFromStops = newIntArray(limit, NOT_SET);
        this.transferTimes = newIntArray(limit, NOT_SET);
    }

    @Override
    public void setInitialTime(int round, int stop, int time) {
        assert time > 0;
        assert stop > 0;

        final int index = findOrCreateStopIndex(round, stop);
        times[index] = time;
    }

    @Override
    public void transitToStop(int round, int stop, int time, int fromPattern, int boardStop, int tripIndex, int boardTime, boolean bestTime) {
        assert time > 0;
        assert fromPattern >= 0;
        assert boardStop > 0;
        assert tripIndex >= 0;
        assert boardTime > 0;

        final int index = findOrCreateStopIndex(round, stop);

        transitTimes[index] = time;
        previousPatterns[index] = fromPattern;
        previousTrips[index] = tripIndex;
        boardTimes[index] = boardTime;
        boardStops[index] = boardStop;

        if(bestTime) {
            times[index] = time;
            transferFromStops[index] = NOT_SET;
        }
    }

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    @Override
    public void transferToStop(int round, int fromStop, StopArrival toStopArrival, int time) {
        final int stop = toStopArrival.stop();
        final int transferTime = toStopArrival.durationInSeconds();

        assert time > 0;
        assert fromStop > 0;
        assert transferTime < 0;


        final int index = findOrCreateStopIndex(round, stop);
        times[index] = time;
        transferFromStops[index] = fromStop;
        transferTimes[index] = transferTime;
    }

    public Cursor newCursor() {
        return new Cursor();
    }



    private int nextAvailable() {
        // Skip the first element, index 0 is not used for optimaziations reasons
        return ++size;
    }

    private int findOrCreateStopIndex(final int round, final int stop) {
        if(stateStopIndex[round][stop] == 0) {
            stateStopIndex[round][stop] = nextAvailable();
        }
        return stateStopIndex[round][stop];
    }

    public final class Cursor implements StopStateCursor, StopState {
        private int round;
        private int stop;
        private int cursor;


        /* Implement StopStateCursor */

        @Override
        public final StopState stop(int round, int stop) {
            this.cursor = stateStopIndex[round][stop];
            this.round = round;
            this.stop = stop;
            return this;
        }

        @Override
        public final boolean stopNotVisited(int round, int stop) {
            return stateStopIndex[round][stop] == 0;
        }


        /* Implement StopState */

        @Override
        public final int time() {
            return times[cursor];
        }

        @Override
        public final int transitTime() {
            return transitTimes[cursor];
        }

        @Override
        public final boolean arrivedByTransit() {
            return transitTimes[cursor] != UNREACHED;
        }

        @Override
        public final int pattern() {
            return previousPatterns[cursor];
        }

        @Override
        public final int trip() {
            return previousTrips[cursor];
        }

        @Override
        public final int transferTime() {
            return transferTimes[cursor];
        }

        @Override
        public final int boardStop() {
            return boardStops[cursor];
        }

        @Override
        public final int boardTime() {
            return boardTimes[cursor];
        }

        @Override
        public final int transferFromStop() {
            return transferFromStops[cursor];
        }

        @Override
        public final boolean arrivedByTransfer() {
            return transferFromStops[cursor] != NOT_SET;
        }

        @Override
        public final String toString() {
            return asString("int array", round, stop);
        }
    }
}
