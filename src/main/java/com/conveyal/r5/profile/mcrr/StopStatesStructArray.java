package com.conveyal.r5.profile.mcrr;


import static com.conveyal.r5.profile.mcrr.StopState.NOT_SET;


public final class StopStatesStructArray implements StopStateCollection {

    private final State[][] stops;


    public StopStatesStructArray(int rounds, int stops) {
        this.stops = new State[rounds][stops];
    }

    @Override
    public void setInitalTime(int round, int stop, int time) {
        findOrCreateStopIndex(round, stop).time = time;
    }

    @Override
    public void transitToStop(int round, int stop, int time, int fromPattern, int boardStop, int tripIndex, int boardTime, boolean bestTime) {
        final State state = findOrCreateStopIndex(round, stop);

        state.transitTime = time;
        state.previousPattern = fromPattern;
        state.previousTrip = tripIndex;
        state.boardTime = boardTime;
        state.boardStop = boardStop;

        if(bestTime) {
            state.time = time;
            state.transferFromStop = NOT_SET;
        }
    }

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    @Override
    public void transferToStop(int round, int stop, int time, int fromStop, int transferTime) {
        final State state = findOrCreateStopIndex(round, stop);
        state.time = time;
        state.transferFromStop = fromStop;
        state.transferTime = transferTime;
    }

    public final int time(int round, int stop) {
        return stops[round][stop].time;
    }

    public Cursor newCursor() {
        return new Cursor();
    }

    private State findOrCreateStopIndex(final int round, final int stop) {
        if(stops[round][stop] == null) {
            stops[round][stop] = new State();
        }
        return stops[round][stop];
    }

    public class Cursor implements StopStateCursor {

        public State stop(int round, int stop) {
            return stops[round][stop];
        }

        @Override
        public boolean stopNotVisited(int round, int stop) {
            return stops[round][stop] == null;
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
