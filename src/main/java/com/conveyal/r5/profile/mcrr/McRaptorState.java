package com.conveyal.r5.profile.mcrr;

import java.util.BitSet;

import static com.conveyal.r5.profile.mcrr.IntUtils.newIntArray;

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
    /**
     * This value essentially serves as Infinity for ints - it's bigger than every other number.
     * It is the travel time to a transit stop or a target before that stop or target is ever reached.
     * Be careful when propagating travel times from stops to targets, adding something to UNREACHED will cause overflow.
     */
    public static final int UNREACHED = Integer.MAX_VALUE;

    /** State for the previous round (one less transfer). */
    public McRaptorState previous;

    /** Departure time for the search producing this state. */
    private int departureTime;

    /** Maximum duration of trips stored by this RaptorState */
    private final int maxDurationSeconds;

    /** Stop the search when the time excids the max time limit. */
    private int maxTimeLimit;

    /** Best times to reach each stop, whether via a transfer or via transit directly. */
    public final int[] bestTimes;

    /** The best times for reaching stops via transit rather than via a transfer from another stop */
    public final int[] bestTransitTimes;

    /**
     * The previous pattern used to get to this stop, parallel to bestTransitTimes.
     * When there is a transfer, bestTransitTimes will contain the time that the pattern in
     * previousPatterns arrived, whereas bestTimes will contain the time the transfer arrived (these are kept separate
     * to keep the router from blowing past the walk limit by stringing multiple transfers together). The previous pattern
     * for the transfer can be found by looking up the stop at which the transfer originated in transferStop, and looking
     * at the previous pattern at that stop. Transfers are done at the end of a round but do not have a separate round,
     * so a single RaptorState represents everything that happened in a round, including riding transit vehicles and any
     * possible transfers from those transit vehicles to other stops.
     */
    public final int[] previousPatterns;

    public final int[] previousTrips;

    public final int[] boardTimes;

    public final int[] transferTimes;

    /** The stop the previous pattern was boarded at */
    public final int[] previousStop;

    /** If this stop is optimally reached via a transfer, the stop we transferred from */
    public final int[] transferStop;

    /** Stops touched by transit search */
    public final BitSet transitStopsTouched;

    /** Stops touched by transit or transfers */
    public final BitSet bestStopsTouched;

    private final BitSet stopTimesImproved;

    /** create a RaptorState for a network with a particular number of stops, and a given maximum duration */
    public McRaptorState(int nStops, int maxDurationSeconds) {
        this.bestTimes = newIntArray(nStops, UNREACHED);
        this.bestTransitTimes = newIntArray(nStops, UNREACHED);

        this.previousPatterns = newIntArray(nStops, -1);
        this.previousStop = newIntArray(nStops, -1);
        this.transferStop = newIntArray(nStops, -1);
        this.previousTrips = newIntArray(nStops, -1);
        this.boardTimes = newIntArray(nStops, -1);
        this.transferTimes = newIntArray(nStops, -1);

        this.transitStopsTouched = new BitSet(nStops);
        this.bestStopsTouched = new BitSet(nStops);
        this.maxDurationSeconds = maxDurationSeconds;
        this.stopTimesImproved = new BitSet(nStops);
    }


    /**
     * Set this state to the min values found in this state or the other passed in (used in Range RAPTOR).
     * Since this is used to progress between rounds, does not copy stopsTouched data.
     */
    public void min(McRaptorState other) {
        for (int stop = other.stopTimesImproved.nextSetBit(0); stop >= 0; stop = other.stopTimesImproved.nextSetBit(stop + 1)) {
            //for (int stop = 0; stop < nStops; stop++) {
            // prefer times from other when breaking tie as other is earlier in RAPTOR search and thus has fewer transfers
            if (other.bestTimes[stop] <= this.bestTimes[stop]) {
                this.stopTimesImproved.set(stop);
                this.bestTimes[stop] = other.bestTimes[stop];
                this.transferStop[stop] = other.transferStop[stop];
            }
            if (other.bestTransitTimes[stop] <= this.bestTransitTimes[stop]) {
                this.stopTimesImproved.set(stop);
                this.bestTransitTimes[stop] = other.bestTransitTimes[stop];
                this.previousPatterns[stop] = other.previousPatterns[stop];
                this.previousStop[stop] = other.previousStop[stop];
            }
        }
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the nonTransferTime
     *
     * @return if the time was optimal
     */
    public void transitToStop(int stop, int time, int fromPattern, int fromStop, int tripIndex, int boardTime) {
        if (time > maxTimeLimit) {
            return;
        }

        if (time < bestTransitTimes[stop]) {
            stopTimesImproved.set(stop);
            bestTransitTimes[stop] = time;
            transitStopsTouched.set(stop);
            previousPatterns[stop] = fromPattern;
            previousTrips[stop] = tripIndex;
            boardTimes[stop] = boardTime;
            previousStop[stop] = fromStop;


            // nonTransferTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
            // enter this conditional it has already been updated.
            if (time < bestTimes[stop]) {
                bestTimes[stop] = time;
                bestStopsTouched.set(stop);
                transferStop[stop] = -1;
            }
        }
    }

    /**
     * Set the time at a transit stop iff it is optimal. This sets both the bestTime and the nonTransferTime
     *
     */
    public void transferToStop(int stop, int time, int fromStop, int transferTime) {

        if (time > maxTimeLimit) {
            return;
        }
        // nonTransferTimes upper bounds bestTimes so we don't need to update wait time and in-vehicle time here, if we
        // enter this conditional it has already been updated.
        if (time < bestTimes[stop]) {
            stopTimesImproved.set(stop);
            bestTimes[stop] = time;
            bestStopsTouched.set(stop);
            transferStop[stop] = fromStop;
            transferTimes[stop] = transferTime;
        }
    }

    public void setInitalTime(int stop, int time) {
        stopTimesImproved.set(stop);
        bestTimes[stop] = time;
        bestStopsTouched.set(stop);
    }

    public void setDepartureTime(int departureTime) {
        this.departureTime = departureTime;
        this.maxTimeLimit = departureTime + maxDurationSeconds;
    }
}
