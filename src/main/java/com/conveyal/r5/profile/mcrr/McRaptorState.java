package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Tracks the state of a RAPTOR search, specifically the best arrival times at each transit stop at the end of a
 * particular round, along with associated data to reconstruct paths etc.
 * <p>
 * This is grouped into a separate class (rather than just having the fields in the raptor worker class) because we
 * need to make copies of it when doing Monte Carlo frequency searches. While performing the range-raptor search,
 * we keep performing raptor searches at different departure times, stepping back in time, but operating on the same
 * set of states (one for each round). But after each one of those departure time searches, we want to run sub-searches
 * with different randomly selected schedules (the Monte Carlo draws). We don't want those sub-searches to invalidate
 * the states for the ongoing range-raptor search, so we make a protective copy.
 * <p>
 * Note that this represents the entire state of the RAPTOR search for a single round, rather than the state at
 * a particular vertex (transit stop), as is the case with State objects in other search algorithms we have.
 *
 * @author mattwigway
 */
public class McRaptorState {

    /** State for the previous round (one less transfer). */
    public McRaptorState previous;

    /** Departure time for the search producing this state. */
    private int departureTime;

    /** Best times to reach each stop, whether via a transfer or via transit directly. */
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

    public int[] previousTrips;

    public int[] boardTimes;

    public int[] transferTimes;

    /** The stop the previous pattern was boarded at */
    public int[] previousStop;

    /** If this stop is optimally reached via a transfer, the stop we transferred from */
    public int[] transferStop;

    /** Stops touched by transit search */
    public BitSet nonTransferStopsTouched;

    /** Stops touched by transit or transfers */
    public BitSet bestStopsTouched;

    /** Maximum duration of trips stored by this RaptorState */
    private int maxDurationSeconds;

    private BitSet stopTimesImproved;

    /** create a RaptorState for a network with a particular number of stops, and a given maximum duration */
    public McRaptorState(int nStops, int maxDurationSeconds) {
        this.bestTimes = new int[nStops];
        this.bestNonTransferTimes = new int[nStops];

        Arrays.fill(bestTimes, FastRaptorWorker.UNREACHED);
        Arrays.fill(bestNonTransferTimes, FastRaptorWorker.UNREACHED);

        this.previousPatterns = new int[nStops];
        this.previousStop = new int[nStops];
        this.transferStop = new int[nStops];
        this.previousTrips = new int[nStops];
        this.boardTimes = new int[nStops];
        this.transferTimes = new int[nStops];
        Arrays.fill(previousPatterns, -1);
        Arrays.fill(previousStop, -1);
        Arrays.fill(transferStop, -1);
        Arrays.fill(previousTrips, -1);
        Arrays.fill(boardTimes, -1);
        Arrays.fill(transferTimes, -1);

        this.nonTransferStopsTouched = new BitSet(nStops);
        this.bestStopsTouched = new BitSet(nStops);
        this.maxDurationSeconds = maxDurationSeconds;
        this.stopTimesImproved = new BitSet(nStops);
    }


    /**
     * Set this state to the min values found in this state or the other passed in (used in Range RAPTOR).
     * Since this is used to progress between rounds, does not copy stopsTouched data.
     */
    public void min(McRaptorState other) {
        int nStops = this.bestTimes.length;
        for (int stop = other.stopTimesImproved.nextSetBit(0); stop >= 0; stop = other.stopTimesImproved.nextSetBit(stop + 1)) {
            //for (int stop = 0; stop < nStops; stop++) {
            // prefer times from other when breaking tie as other is earlier in RAPTOR search and thus has fewer transfers
            if (other.bestTimes[stop] <= this.bestTimes[stop]) {
                this.stopTimesImproved.set(stop);
                this.bestTimes[stop] = other.bestTimes[stop];
                this.transferStop[stop] = other.transferStop[stop];
            }
            if (other.bestNonTransferTimes[stop] <= this.bestNonTransferTimes[stop]) {
                this.stopTimesImproved.set(stop);
                this.bestNonTransferTimes[stop] = other.bestNonTransferTimes[stop];
                this.previousPatterns[stop] = other.previousPatterns[stop];
                this.previousStop[stop] = other.previousStop[stop];
            }
        }
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the nonTransferTime
     *
     * @param transfer if true, this was reached via transfer/initial walk
     * @return if the time was optimal
     */
    public boolean setTimeAtStop(int stop, int time, int fromPattern, int fromStop, int waitTime, int inVehicleTime, boolean transfer, int tripIndex, int boardTime, int transferTime) {
        if (time > departureTime + maxDurationSeconds) return false;

        boolean optimal = false;
        if (!transfer && time < bestNonTransferTimes[stop]) {
            stopTimesImproved.set(stop);
            bestNonTransferTimes[stop] = time;
            nonTransferStopsTouched.set(stop);
            previousPatterns[stop] = fromPattern;
            previousTrips[stop] = tripIndex;
            boardTimes[stop] = boardTime;
            previousStop[stop] = fromStop;

            optimal = true;
        }

        // nonTransferTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        if (time < bestTimes[stop]) {
            stopTimesImproved.set(stop);
            bestTimes[stop] = time;
            bestStopsTouched.set(stop);
            if (transfer) {
                transferStop[stop] = fromStop;
                transferTimes[stop] = transferTime;
            } else {
                transferStop[stop] = -1;
            }
            optimal = true;
        }

        return optimal;
    }

    public void setInitalTime(int stop, int time) {
        stopTimesImproved.set(stop);
        bestTimes[stop] = time;
        bestStopsTouched.set(stop);
    }

    /** Debug function: dump the path up to this state as a string */
    public String dump (int stop) {
        Path p = new McPathBuilder().extractPathForStop(this, stop);

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < p.length; i++) {
            sb.append(String.format("Stop %5d at %5d, reached by pattern %5d from stop %5d\n", p.alightStops[i], p.alightTimes[i], p.patterns[i], p.boardStops[i]));
        }

        return sb.toString();
    }

    public void setDepartureTime(int departureTime) {
        this.departureTime = departureTime;
    }
}
