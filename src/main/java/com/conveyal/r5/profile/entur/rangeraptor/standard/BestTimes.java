package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.util.BitSetIterator;
import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.BitSet;

import static com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalState.UNREACHED;
import static com.conveyal.r5.profile.entur.util.IntUtils.newIntArray;

final class BestTimes {
    /** Format: [time] [reached current round] [reached previous round] */
    private static final String FORMAT_STRING = "%8s %c %c";

    /** The best times to reach a stop (ALL rounds). */
    private final int[] times;
    private final int[] timesLastRound;

    /** Stops touched by transit or transfers in the CURRENT round. */
    private BitSet reachedCurrentRound;

    /** Stops touched by transit or transfers in LAST round. */
    private BitSet reachedLastRound;


    BestTimes(int nStops) {
        this.times = newIntArray(nStops, UNREACHED);
        this.timesLastRound = newIntArray(nStops, UNREACHED);
        this.reachedCurrentRound = new BitSet(nStops);
        this.reachedLastRound = new BitSet(nStops);
    }

    final boolean isCurrentRoundEmpty() {
        return reachedCurrentRound.isEmpty();
    }

    final boolean isReached(final int stop) {
        return times[stop] != UNREACHED;
    }

    final boolean isReachedLastRound(int stop) {
        return reachedLastRound.get(stop);
    }

    final void setTime(final int stop, final int time) {
        times[stop] = time;
        reachedCurrentRound.set(stop);
    }

    /**
     * @return true iff new best time is updated
     */
    final boolean updateNewBestTime(final int stop, int time) {
        if(isBestTime(stop, time)) {
            setTime(stop, time);
            return true;
        }
        return false;
    }

    private boolean isBestTime(final int stop, int time) {
        return time < times[stop];
    }

    final int time(final int stop) {
        return times[stop];
    }

    final BitSetIterator stopsReachedLastRound() {
        return new BitSetIterator(reachedLastRound);
    }

    final BitSetIterator stopsReachedCurrentRound() {
        return new BitSetIterator(reachedCurrentRound);
    }

    final void gotoNextRound() {
        BitSet tmp = reachedLastRound;
        reachedLastRound = reachedCurrentRound;
        reachedCurrentRound = tmp;
        reachedCurrentRound.clear();

        System.arraycopy(times, 0, timesLastRound, 0, times.length);
    }

    final void clearCurrent() {
        reachedCurrentRound.clear();
    }

    String toString(int stop) {
        return String.format(FORMAT_STRING, timeToString(stop), mark(stop, reachedCurrentRound), mark(stop, reachedLastRound));
    }

    private static char mark(int stop, BitSet bitSet) {
        return bitSet.get(stop) ? 'X' : ' ';
    }

    private String timeToString(int stop) {
        return TimeUtils.timeToStrLong(times[stop], UNREACHED);
    }
}
