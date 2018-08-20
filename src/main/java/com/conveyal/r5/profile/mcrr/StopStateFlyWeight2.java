package com.conveyal.r5.profile.mcrr;


import static com.conveyal.r5.profile.mcrr.StopState.NOT_SET;
import static com.conveyal.r5.profile.mcrr.StopState.UNREACHED;
import static com.conveyal.r5.util.TimeUtils.timeToString;

public final class StopStateFlyWeight2 implements StopStateCollection {

    private int size = 0;

    private final int[][] stateStopIndex;


    private final State[] stops;


    public StopStateFlyWeight2(int rounds, int stops) {
        this.stateStopIndex = new int[rounds][stops];

        final int limit = 3 * stops;
        this.stops = new State[limit];
        this.stops[size] = new State();

    }

    @Override
    public void setInitalTime(int round, int stop, int time) {
        final int index = findOrCreateStopIndex(round, stop);
        stops[index].time = time;
    }

    @Override
    public void transitToStop(int round, int stop, int time, int fromPattern, int boardStop, int tripIndex, int boardTime, boolean bestTime) {
        final int index = findOrCreateStopIndex(round, stop);

        stops[index].transitTime = time;
        stops[index].previousPattern = fromPattern;
        stops[index].previousTrip = tripIndex;
        stops[index].boardTime = boardTime;
        stops[index].boardStop = boardStop;

        if(bestTime) {
            stops[index].time = time;
            stops[index].transferFromStop = NOT_SET;
        }
    }

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    @Override
    public void transferToStop(int round, int stop, int time, int fromStop, int transferTime) {
        final int index = findOrCreateStopIndex(round, stop);
        stops[index].time = time;
        stops[index].transferFromStop = fromStop;
        stops[index].transferTime = transferTime;
    }

    public final int time(int round, int stop) {
        final int index = stateStopIndex[round][stop];
        return stops[index].time;
    }

    public Cursor newCursor() {
        return new Cursor();
    }


    private String stopToString(int round, int stop) {
        return stops[stateStopIndex[round][stop]].asString();
    }


    private int nextAvailable() {
        // Skip the first element, index 0 is not used for optimaziations reasons
        ++size;
        stops[size] = new State();
        return size;
    }

    private static String intToString(int value) { return value == -1 ? "" : Integer.toString(value); }

    private int findOrCreateStopIndex(final int round, final int stop) {
        if(stateStopIndex[round][stop] == 0) {
            stateStopIndex[round][stop] = nextAvailable();
        }
        return stateStopIndex[round][stop];
    }

    public class Cursor implements StopStateCursor {
        private State currentStop;

        public State stop(int round, int stop) {
            this.currentStop = stops[stateStopIndex[round][stop]];
            return currentStop;
        }
    }

    static class State implements StopState {
        int time = UNREACHED;
        int transitTime = UNREACHED;
        int previousPattern = NOT_SET;
        int previousTrip = NOT_SET;
        int boardTime = UNREACHED;
        int transferTime = NOT_SET;
        int boardStop = NOT_SET;
        int transferFromStop = NOT_SET;

        @Override
        public final int time() {
            return time;
        }

        @Override
        public int transitTime() {
            return transitTime;
        }

        @Override
        public boolean isTransitTimeSet() {
            return transitTime != UNREACHED;
        }

        @Override
        public int previousPattern() {
            return previousPattern;
        }

        @Override
        public int previousTrip() {
            return previousTrip;
        }

        @Override
        public int transferTime() {
            return transferTime;
        }

        @Override
        public int boardStop() {
            return boardStop;
        }

        @Override
        public int boardTime() {
            return boardTime;
        }

        @Override
        public int transferFromStop() {
            return transferFromStop;
        }

        @Override
        public boolean arrivedByTransfer() {
            return transferFromStop != NOT_SET;
        }

        @Override
        public String toString() {
            return asString();
        }
    }
}
