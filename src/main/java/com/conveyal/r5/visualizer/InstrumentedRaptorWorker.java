package com.conveyal.r5.visualizer;

import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.PropagatedTimesStore;
import com.conveyal.r5.profile.RaptorState;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.map.TIntIntMap;

import java.util.BitSet;
import java.util.concurrent.CountDownLatch;

/**
 * An instrumented version of RaptorWorker that lets one see what's going on in the search process
 */
public class InstrumentedRaptorWorker extends RaptorWorker {
    /** Should this worker pause after each round? */
    public boolean pauseAfterRound = true;

    /** Should this worker pause after the scheduled search */
    public boolean pauseAfterScheduledSearch = true;

    /** Should this worker pause after each frequency search */
    public boolean pauseAfterFrequencySearch = true;

    /** Should this worker pause after each departure minute */
    public boolean pauseAfterDepartureMinute = true;

    public int runningMs = 0;

    /** Is this worker paused? */
    public boolean paused = false;

    /** results of this operation, or null if not finished */
    public PropagatedTimesStore results = null;

    /** Latch to pause caller */
    CountDownLatch callerLatch;

    /** Latch to pause search */
    CountDownLatch searchLatch;

    /** are we running a scheduled search? */
    public boolean scheduledSearch;

    public BitSet stopsTouchedThisMinuteScheduled = new BitSet();
    public BitSet stopsTouchedThisMinuteFrequency = new BitSet();
    public BitSet allStopsTouchedThisMinute = new BitSet();

    public BitSet patternsExploredThisRound = new BitSet();
    public BitSet patternsExploredThisMinuteScheduled = new BitSet();
    public BitSet patternsExploredThisMinuteFrequency = new BitSet();
    public BitSet patternsExploredThisMinute = new BitSet();

    public int departureTime;

    public RaptorState raptorState;

    /**
     * The state of the raptor worker, which is stored as a class field. It is updated in the pause()
     * function before notifying the caller, to ensure the caller has a consistent view of the state.
     */
    public RaptorWorkerState workerState;

    public InstrumentedRaptorWorker(TransitLayer data, LinkedPointSet targets, ProfileRequest req) {
        super(data, targets, req);
    }

    @Override
    public boolean doOneRound(RaptorState inputState, RaptorState outputState, boolean useFrequencies) {
        // copy in patterns that will be explored on the next round now that output tables have been created
        this.patternsExploredThisRound.clear();
        this.patternsExploredThisRound.or(patternsTouchedThisRound);
        this.patternsExploredThisMinute.or(patternsTouchedThisRound);

        if (scheduledSearch) {
            this.patternsExploredThisMinuteScheduled.or(patternsTouchedThisRound);
        } else {
            this.patternsExploredThisMinuteFrequency.or(patternsTouchedThisRound);
        }

        boolean val = super.doOneRound(inputState, outputState, useFrequencies);

        raptorState = outputState;

        if (pauseAfterRound) pause();

        return val;
    }

    @Override
    protected void doTransfers (RaptorState state) {
        // do transfers will clear stops touched, so copy them here
        // copy output into output tables
        if (scheduledSearch) {
            stopsTouchedThisMinuteScheduled.or(stopsTouchedThisRound);
        } else {
            stopsTouchedThisMinuteFrequency.or(stopsTouchedThisRound);
        }

        allStopsTouchedThisMinute.or(stopsTouchedThisRound);

        super.doTransfers(state);
    }

    @Override
    public void runRaptorScheduled(TIntIntMap initialStops, int departureTime) {
        scheduledSearch = true;
        this.patternsExploredThisMinuteScheduled.clear();
        this.stopsTouchedThisMinuteScheduled.clear();
        this.allStopsTouchedThisMinute.clear();
        this.patternsExploredThisMinute.clear();
        this.departureTime = departureTime;

        super.runRaptorScheduled(initialStops, departureTime);
        if (pauseAfterScheduledSearch) pause();
    }

    @Override
    public RaptorState runRaptorFrequency(int departureTime) {
        this.patternsExploredThisMinuteFrequency.clear();
        this.stopsTouchedThisMinuteFrequency.clear();
        this.allStopsTouchedThisMinute.clear();
        this.patternsExploredThisMinute.clear();

        // copy back in scheduled search into nonspecific outputs
        this.patternsExploredThisMinute.or(this.patternsExploredThisMinuteScheduled);
        this.allStopsTouchedThisMinute.or(this.stopsTouchedThisMinuteScheduled);

        scheduledSearch = false;
        RaptorState state = super.runRaptorFrequency(departureTime);
        if (pauseAfterFrequencySearch) pause();
        return state;
    }

    @Override
    public void advanceToNextMinute () {
        super.advanceToNextMinute();
        if (this.pauseAfterDepartureMinute) this.pause();

        this.patternsExploredThisMinute.clear();
        this.patternsExploredThisMinuteScheduled.clear();
    }

    /** Run RAPTOR in its own thread that will be paused and unpaused as the search progresses */
    public void runRaptorAsync (TIntIntMap accessTimes, PointSetTimes nonTransitTimes, TaskStatistics ts) {
        workerState = new RaptorWorkerState();
        workerState.cumulativeMs = 0;

        new Thread(() -> {
            // start paused
            pause();
            results = super.runRaptor(accessTimes, nonTransitTimes, ts);
            // pause at end to unstick caller
            pause();
        }).start();
    }

    /** convenience, wraps wait in try/catch */
    private void pause () {
        // do this here before unpausing calling thread
        RaptorWorkerState ret = new RaptorWorkerState();
        ret.cumulativeMs = runningMs;
        ret.round = this.round;
        ret.iteration = statesEachIteration.size();
        ret.departureTime = departureTime;
        ret.frequencySearch = !scheduledSearch;

        // safe to use and as bitsets are initialized when raptorworkerstate is created.
        ret.stopsTouchedThisRound.or(stopsTouchedThisRound);
        ret.stopsTouchedThisMinuteFrequency.or(this.stopsTouchedThisMinuteFrequency);
        ret.stopsTouchedThisMinuteScheduled.or(this.stopsTouchedThisMinuteScheduled);
        ret.allStopsTouchedThisMinute.or(this.allStopsTouchedThisMinute);

        ret.patternsExploredThisRound.or(this.patternsExploredThisRound);
        ret.patternsExploredThisMinute.or(this.patternsExploredThisMinute);
        ret.patternsExploredThisMinuteFrequency.or(this.patternsExploredThisMinuteFrequency);
        ret.patternsExploredThisMinuteScheduled.or(this.patternsExploredThisMinuteScheduled);

        ret.raptorState = raptorState;
        ret.complete = results != null;

        // atomic write
        this.workerState = ret;

        // unpause calling thread if it is paused
        try {
            if (callerLatch != null) callerLatch.countDown();
        } catch (IllegalMonitorStateException e) {
            // calling thread not waiting, happens at start of search
        }

        // pause this thread
        try {
            searchLatch = new CountDownLatch(1);
            searchLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public RaptorWorkerState unpause () {
        // notify search process to continue
        searchLatch.countDown();

        // pause calling thread until search process pauses again
        try {
            callerLatch = new CountDownLatch(1);
            callerLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        return this.workerState;
    }
}
