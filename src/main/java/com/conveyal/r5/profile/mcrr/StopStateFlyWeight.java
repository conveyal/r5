package com.conveyal.r5.profile.mcrr;


import static com.conveyal.r5.profile.mcrr.IntUtils.newIntArray;
import static com.conveyal.r5.profile.mcrr.TimeUtils.timeToString;

public final class StopStateFlyWeight implements StopState {

    private int size = 0;
    private int cursor = NOT_SET;

    private final int[] times;
    private final int[] transitTimes;
    private final int[] previousPatterns;
    private final int[] previousTrips;
    private final int[] boardTimes;
    private final int[] transferTimes;
    private final int[] boardStops;
    private final int[] transferFromStops;


    StopStateFlyWeight(int size) {
        this.times = newIntArray(size, UNREACHED);

        this.boardStops = newIntArray(size, NOT_SET);
        this.transitTimes = newIntArray(size, UNREACHED);
        this.previousPatterns = newIntArray(size, NOT_SET);
        this.previousTrips = newIntArray(size, NOT_SET);
        this.boardTimes = newIntArray(size, UNREACHED);

        this.transferFromStops = newIntArray(size, NOT_SET);
        this.transferTimes = newIntArray(size, NOT_SET);
    }

    public void transitToStop(int index, int time, int fromPattern, int boardStop, int tripIndex, int boardTime, boolean bestTime) {
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
     * Set the time at a transit index iff it is optimal. This sets both the best time and the nonTransferTime
     */
    public void transferToStop(int index, int time, int fromStop, int transferTime) {
        times[index] = time;
        transferFromStops[index] = fromStop;
        transferTimes[index] = transferTime;
    }

    public void setInitalTime(int index, int time) {
        times[index] = time;
    }

    public void setCursor(int cursor) {
        this.cursor = cursor;
    }

    @Override
    public final int time() {
        return times[cursor];
    }

    public final int time(int index) {
        return times[index];
    }
    @Override
    public int transitTime() {
        return transitTimes[cursor];
    }

    @Override
    public boolean isTransitTimeSet() {
        return transitTimes[cursor] != UNREACHED;
    }

    @Override
    public int previousPattern() {
        return previousPatterns[cursor];
    }

    @Override
    public int previousTrip() {
        return previousTrips[cursor];
    }

    @Override
    public int transferTime() {
        return transferTimes[cursor];
    }

    @Override
    public int boardStop() {
        return boardStops[cursor];
    }

    @Override
    public int boardTime() {
        return boardTimes[cursor];
    }

    @Override
    public int transferFromStop() {
        return transferFromStops[cursor];
    }

    @Override
    public boolean arrivedByTransfer() {
        return transferFromStops[cursor] != NOT_SET;
    }

    public int nextAvailable() {
        // Skip the first element, index 0 is not used for optimaziations reasons
        return ++size;
    }


    static final String[] HEADERS = {
            "- TRANSFER FROM -   ----------- TRANSIT -----------",
            " Time   Stop  Dur    Time B.Stop B.Time  Pttrn Trip"
    };

    public String stopToString(int stopIndex) {
        return String.format("%5s %6s %4s   %5s %6s %6s %6s %4s",
                timeToString(times[stopIndex], UNREACHED),
                intToString(transferFromStops[stopIndex]),
                intToString(transferTimes[stopIndex]),
                timeToString(transitTimes[stopIndex], UNREACHED),
                intToString(boardStops[stopIndex]),
                timeToString(boardTimes[stopIndex], UNREACHED),
                intToString(previousPatterns[stopIndex]),
                intToString(previousTrips[stopIndex])
        );
    }

    private static String intToString(int value) { return value == -1 ? "" : Integer.toString(value); }
}
