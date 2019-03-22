package com.conveyal.r5.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Tracks the state of a RAPTOR search, specifically the best arrival times at each transit stop at the end of a
 * particular round, along with associated data to reconstruct paths etc.
 *
 * This is grouped into a separate class (rather than just having the fields in the raptor worker class) because we
 * need to make copies of it when doing Monte Carlo frequency searches. While performing the range-raptor search,
 * we keep performing raptor searches at different departure times, stepping back in time, but operating on the same
 * set of states (one for each round). But after each one of those departure time searches, we want to run sub-searches
 * with different randomly selected schedules (the Monte Carlo draws). We don't want those sub-searches to invalidate
 * the states for the ongoing range-raptor search, so we make a protective copy.
 *
 * Note that this represents the entire state of the RAPTOR search for a single round, rather than the state at
 * a particular vertex (transit stop), as is the case with State objects in other search algorithms we have.
 *
 * @author mattwigway
 */
public class RaptorState {
    private static final Logger LOG = LoggerFactory.getLogger(RaptorState.class);

    /** State for the previous round (one less transfer). */
    public RaptorState previous;

    /** Departure time for the search producing this state. */
    public int departureTime;

    /** Best times to reach each stop, whether via a transfer or via transit directly. */
    public int[] bestTimes;

    /** The best times for reaching stops via transit rather than via a transfer from another stop.
     * Used to record paths, keep number of transfers and transfer walking distance within requested limits, etc.  */
    public int[] bestNonTransferTimes;

    /** Cumulative transit wait time for the best path to each stop, parallel to bestNonTransferTimes. */
    public int[] nonTransferWaitTime;

    /** Cumulative in-vehicle travel time for the best path to each stop, parallel to bestNonTransferTimes. */
    public int[] nonTransferInVehicleTravelTime;

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

    /** Stops touched by transit search. This is used in an optimization to only check for transfers
     * from stops that have been "touched" (i.e. updated with a lower time based on a direct egress rather than a
     * transfer in this round) */
    public BitSet nonTransferStopsTouched;

    /** Stops touched by transit or transfers.  Similar to nonTransferStopsTouched, but used to flag what should be
     * checked in the next round, rather than what should be checked when doing transfers at the end of this
     * round.*/
    public BitSet bestStopsTouched;

    /** Maximum duration of trips stored by this RaptorState */
    public int maxDurationSeconds;

    /** create a RaptorState for a network with a particular number of stops, and a given maximum duration */
    public RaptorState (int nStops, int maxDurationSeconds) {
        this.bestTimes = new int[nStops];
        this.bestNonTransferTimes = new int[nStops];

        Arrays.fill(bestTimes, FastRaptorWorker.UNREACHED);
        Arrays.fill(bestNonTransferTimes, FastRaptorWorker.UNREACHED);

        this.previousPatterns = new int[nStops];
        this.previousStop = new int[nStops];
        this.transferStop = new int[nStops];
        Arrays.fill(previousPatterns, -1);
        Arrays.fill(previousStop, -1);
        Arrays.fill(transferStop, -1);

        this.nonTransferWaitTime = new int[nStops];
        this.nonTransferInVehicleTravelTime = new int[nStops];
        this.nonTransferStopsTouched = new BitSet(nStops);
        this.bestStopsTouched = new BitSet(nStops);
        this.maxDurationSeconds = maxDurationSeconds;
    }

    /**
     * Copy constructor, does not copy touchedStops data (leaves it empty).
     */
    private RaptorState(RaptorState state) {
        this.bestTimes = Arrays.copyOf(state.bestTimes, state.bestTimes.length);
        this.bestNonTransferTimes = Arrays.copyOf(state.bestNonTransferTimes, state.bestNonTransferTimes.length);
        this.previousPatterns = Arrays.copyOf(state.previousPatterns, state.previousPatterns.length);
        this.previousStop = Arrays.copyOf(state.previousStop, state.previousStop.length);
        this.transferStop = Arrays.copyOf(state.transferStop, state.transferStop.length);
        this.nonTransferWaitTime = Arrays.copyOf(state.nonTransferWaitTime, state.nonTransferWaitTime.length);
        this.nonTransferInVehicleTravelTime = Arrays.copyOf(state.nonTransferInVehicleTravelTime, state.nonTransferInVehicleTravelTime.length);
        this.departureTime = state.departureTime;

        this.previous = state;

        this.nonTransferStopsTouched = new BitSet(state.bestTimes.length);
        this.bestStopsTouched = new BitSet(state.bestTimes.length);

        this.maxDurationSeconds = state.maxDurationSeconds;
    }

    /**
     * Copy this raptor state to progress to the next round.
     * Clears reachedThisRound so should be used only to progress to the next round.
     */
    public RaptorState copy () {
        return new RaptorState(this);
    }

    /**
     * Set this state to the min values found in this state or the other passed in (used in Range RAPTOR).
     * Since this is used to progress between rounds, does not copy stopsTouched data.
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
                this.nonTransferInVehicleTravelTime[stop] = other.nonTransferInVehicleTravelTime[stop];
                // add in any additional wait at the beginning in the range raptor case.
                this.nonTransferWaitTime[stop] = other.nonTransferWaitTime[stop] + (other.departureTime - this.departureTime);
            }
        }
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the nonTransferTime
     *
     * @param transfer if true, this was reached via transfer/initial walk
     * @return if the time was optimal
     */
    public boolean setTimeAtStop(int stop, int time, int fromPattern, int fromStop, int waitTime, int inVehicleTime, boolean transfer) {
        if (time > departureTime + maxDurationSeconds) return false;

        boolean optimal = false;
        if (!transfer && time < bestNonTransferTimes[stop]) {
            bestNonTransferTimes[stop] = time;
            nonTransferStopsTouched.set(stop);
            previousPatterns[stop] = fromPattern;
            previousStop[stop] = fromStop;

            // wait time is not stored after transfers, so copy from pre-transfer
            int totalWaitTime, totalInVehicleTime;

            if (previous == null) {
                // first round, there is no previous wait time or in vehicle time
                totalWaitTime = waitTime;
                totalInVehicleTime = inVehicleTime;
            } else {
                if (previous.transferStop[fromStop] != -1) {
                    // previous stop is optimally reached via a transfer, so grab the wait and in vehicle time from
                    // the stop we transferred from. Otherwise we'll be grabbing the wait time to get to the board stop
                    // on a vehicle, which may be impossible at this round or may simply take longer.
                    int preTransferStop = previous.transferStop[fromStop];
                    totalWaitTime = previous.nonTransferWaitTime[preTransferStop] + waitTime;
                    totalInVehicleTime = previous.nonTransferInVehicleTravelTime[preTransferStop] + inVehicleTime;
                } else {
                    // the stop we boarded at was not the result of a transfer from another stop, grab the cumulative
                    // wait time from that stop
                    totalWaitTime = previous.nonTransferWaitTime[fromStop] + waitTime;
                    totalInVehicleTime = previous.nonTransferInVehicleTravelTime[fromStop] + inVehicleTime;
                }
            }

            if (totalInVehicleTime + totalWaitTime > time - departureTime) {
                LOG.error("Wait and travel time greater than total time.");
            }

            nonTransferWaitTime[stop] = totalWaitTime;
            nonTransferInVehicleTravelTime[stop] = totalInVehicleTime;
            optimal = true;
        }

        // nonTransferTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        if (time < bestTimes[stop]) {
            bestTimes[stop] = time;
            bestStopsTouched.set(stop);
            if (transfer) {
                transferStop[stop] = fromStop;
            } else {
                transferStop[stop] = -1;
            }
            optimal = true;
        }

        return optimal;
    }

    /** Debug function: dump the path up to this state as a string */
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

    public void setDepartureTime(int departureTime) {
        int previousDepartureTime = this.departureTime;
        this.departureTime = departureTime;

        // remove trips that are now too long
        int maxClockTime = departureTime + maxDurationSeconds;
        for (int i = 0; i < bestTimes.length; i++) {
            if (bestTimes[i] > maxClockTime) bestTimes[i] = FastRaptorWorker.UNREACHED;
            if (bestNonTransferTimes[i] > maxClockTime) bestNonTransferTimes[i] = FastRaptorWorker.UNREACHED;
        }

        // handle updating wait
        for (int stop = 0; stop < this.bestTimes.length; stop++) {
            if (this.previousPatterns[stop] > -1) {
                this.nonTransferWaitTime[stop] += previousDepartureTime - departureTime;
            }
        }
    }
}
