package com.conveyal.r5.profile;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Tracks the state of a RAPTOR search. We have a separate class because we need to clone it when doing Monte Carlo
 * frequency searches. Note that this represents the _entire_ state of the RAPTOR search, rather than the state at
 * a particular vertex, as is the case with State objects in other search algorithms we have.
 *
 * @author mattwigway
 */
public class RaptorState {
    /** Previous state (one less transfer) */
    public RaptorState previous;

    /** departure time for this state */
    public int departureTime;

    /** Best times to reach stops, whether via a transfer or via transit directly */
    public int[] bestTimes;

    /** The best times for reaching stops via transit rather than via a transfer from another stop */
    public int[] bestNonTransferTimes;


    /**
     * The previous pattern used to get to this stop, parallel to bestNonTransferTimes.
     * When there is a transfer, bestNonTransferTimes will contain the time that the pattern in
     * previousPatterns arrived, whereas bestTimes will contain the time the transfer arrived (these are kept separate
     * to keep the router from blowing past the walk limit by stringing multiple transfers together). The previous pattern
     * for the transfer can be found by looking up the stop at which the transfer originated in transferStop, and looking
     * at the previous pattern at that stop. Transfers are done at the end of a round but do not have a separate round,
     * so a single RaptorState represents everything that happened in a round, including riding transit vehicles and any
     * possible transfers from those transit vehicles to other stops.
     */
    public int[] previousPatterns;

    /** The stop the previous pattern was boarded at */
    public int[] previousStop;

    /** If this stop is optimally reached via a transfer, the stop we transferred from */
    public int[] transferStop;

    /** create a RaptorState for a network with a particular number of stops */
    public RaptorState (int nStops) {
        this.bestTimes = new int[nStops];
        this.bestNonTransferTimes = new int[nStops];

        Arrays.fill(bestTimes, RaptorWorker.UNREACHED);
        Arrays.fill(bestNonTransferTimes, RaptorWorker.UNREACHED);

        this.previousPatterns = new int[nStops];
        this.previousStop = new int[nStops];
        this.transferStop = new int[nStops];
        Arrays.fill(previousPatterns, -1);
        Arrays.fill(previousStop, -1);
        Arrays.fill(transferStop, -1);
    }

    /**
     * copy constructor, use only when progressing from one round to the next to maintain consistent reachedThisRound data
     */
    private RaptorState(RaptorState state) {
        this.bestTimes = Arrays.copyOf(state.bestTimes, state.bestTimes.length);
        this.bestNonTransferTimes = Arrays.copyOf(state.bestNonTransferTimes, state.bestNonTransferTimes.length);
        this.previousPatterns = Arrays.copyOf(state.previousPatterns, state.previousPatterns.length);
        this.previousStop = Arrays.copyOf(state.previousStop, state.previousStop.length);
        this.transferStop = Arrays.copyOf(state.transferStop, state.transferStop.length);
        this.departureTime = state.departureTime;
        this.previous = state;
    }

    /** Copy this raptor state to progress to the next round. Clears reachedThisRound so should be used only to progress to the next round. */
    public RaptorState copy () {
        return new RaptorState(this);
    }

    /**
     * Set this state to the min values found in this state or the other passed in (used in Range RAPTOR).
     */
    public void min (RaptorState other) {
        int nStops = this.bestTimes.length;
        for (int stop = 0; stop < nStops; stop++) {
            // prefer times from other when breaking tie as other is earlier in RAPTOR search and thus has fewer transfers
            if (other.bestTimes[stop] <= this.bestTimes[stop]) {
                this.bestTimes[stop] = other.bestTimes[stop];
                this.transferStop[stop] = other.transferStop[stop];
            }

            if (other.bestNonTransferTimes[stop] <= this.bestNonTransferTimes[stop]) {
                this.bestNonTransferTimes[stop] = other.bestNonTransferTimes[stop];
                this.previousPatterns[stop] = other.previousPatterns[stop];
                this.previousStop[stop] = other.previousStop[stop];
            }
        }
    }

    /** dump this as a string */
    public String dump (int stop) {
        Path p = new Path(this, stop);

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < p.length; i++) {
            sb.append(String.format("Stop %5d at %5d, reached by pattern %5d from stop %5d\n", p.alightStops[i], p.alightTimes[i], p.patterns[i], p.boardStops[i]));
        }

        return sb.toString();
    }

    /** Do a deep copy of this RaptorState and all parent raptor states. */
    public RaptorState deepCopy() {
        RaptorState state = this;
        RaptorState ret = this.copy();
        RaptorState copy = ret;

        while (state.previous != null) {
            copy.previous = state.previous.copy();
            copy.previous.previous = null;
            state = state.previous;
            copy = copy.previous;
        }

        return ret;
    }
}
