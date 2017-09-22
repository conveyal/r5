package com.conveyal.r5.visualizer;

import com.conveyal.r5.profile.RaptorState;

import java.util.BitSet;

/**
 * Internal state of a RAPTOR worker during the search process.
 */
public class RaptorWorkerState {
    public int msSinceLastPause;
    public int cumulativeMs;
    public int round;
    public BitSet stopsTouchedThisRound = new BitSet();
    public BitSet stopsTouchedThisMinuteScheduled = new BitSet();
    public BitSet stopsTouchedThisMinuteFrequency = new BitSet();
    public BitSet allStopsTouchedThisMinute = new BitSet();

    public BitSet patternsExploredThisRound = new BitSet();
    public BitSet patternsExploredThisMinuteScheduled = new BitSet();
    public BitSet patternsExploredThisMinuteFrequency = new BitSet();
    public BitSet patternsExploredThisMinute = new BitSet();

    public boolean frequencySearch;

    public int departureTime;

    public int iteration;

    public RaptorState raptorState;

    public boolean complete;
}
