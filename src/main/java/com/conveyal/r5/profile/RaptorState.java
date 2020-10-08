package com.conveyal.r5.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;

import static com.conveyal.r5.common.Util.newIntArray;
import static com.conveyal.r5.profile.FastRaptorWorker.ENABLE_OPTIMIZATION_CLEAR_LONG_PATHS;
import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * The state for a single round of a RAPTOR search.
 * This primarily consists of the best known arrival times at each transit stop for a given number of rides, along with
 * associated data to reconstruct paths and efficiently proceed to the next round.
 *
 * These fields are grouped into a separate class (rather than just having the fields in the raptor worker class)
 * because we need to make copies of them when doing Monte Carlo frequency searches. While performing the range-raptor
 * search, we keep performing raptor searches at different departure times, stepping back in time, but operating on the
 * same set of states (one for each round). But after each one of those departure time searches, we want to run
 * sub-searches with different randomly selected schedules (the Monte Carlo draws). We don't want those sub-searches to
 * invalidate the states for the ongoing range-raptor search, so we make a protective copy.
 *
 * Note that this represents the entire state of the RAPTOR search at all stops for a single round, rather than the
 * state at a particular vertex (transit stop), as is the case with State objects in our street search algorithm.
 *
 * Within each round, transit is processed first, then transfers are processed. There are not separate transfer rounds.
 * A single RaptorState represents everything that happened in a round, including riding transit vehicles and any
 * possible transfers from those transit vehicles to other stops.
 */
public class RaptorState {

    private static final Logger LOG = LoggerFactory.getLogger(RaptorState.class);

    /** State for the previous round, representing travel with one less transfer at the same departure time. */
    public RaptorState previous;

    /**
     * Departure time for the search producing this state.
     * This will be decremented at each new departure time when the state is reused in a range-raptor search.
     */
    public int departureTime;

    /**
     * Best (earliest) clock time (not elapsed time) at which we can reach each stop, whether via a transfer or via
     * transit directly. Note that within our rounds transit happens first, then transfers are applied.
     */
    public int[] bestTimes;

    /**
     * Best (earliest) clock time (not elapsed time) at which we can reach each stop directly from a pattern serving
     * that stop rather than via a transfer from another stop. Within a round, these times are built up first,
     * then transfers are applied.
     */
    public int[] bestNonTransferTimes;

    /**
     * Cumulative transit wait time for the best path to each stop, parallel to bestNonTransferTimes.
     * This is "non-transfer" because it reflects arriving at this stop by transit. If you arrive at this stop by
     * a transfer, the time breakdown is instead the one from the transfer source stop.
     */
    public int[] nonTransferWaitTime;

    /**
     * Cumulative in-vehicle travel time for the best path to each stop, parallel to bestNonTransferTimes.
     * This is "non-transfer" because it reflects arriving at this stop by transit. If you arrive at this stop by
     * a transfer, the time breakdown is instead the one from the transfer source stop.
     * TODO instead of accumulating these values couldn't we just derive them when building the paths?
     * TODO could we return paths/times as a tree flowed into an array, with forward-pointers to nodes in the tree?
     */
    public int[] nonTransferInVehicleTravelTime;

    /**
     * The transit pattern used to achieve the travel times recorded for each stop in bestNonTransferTimes.
     * When a transfer improves upon that time, bestNonTransferTimes will still contain the time that the pattern in
     * previousPatterns arrived, but bestTimes will contain the time the transfer arrived and transferStop will show
     * where the transfer originated. So the last pattern used from the perspective of a later round using the transfer
     * is in previousPatterns[transferStop[stop]].
     *
     * Stops that have never been reached by transit will have a value of -1.
     */
    public int[] previousPatterns;

    /**
     * For each stop, if the stop was reached by transit, the stop where the pattern (in previousPatterns) was boarded.
     * If the stop is not (yet) reached by transit, the value is -1. Even if the stop is optimally reached by a
     * transfer, this value may be set reflecting the optimal way to reach it by transit before transfers are applied.
     */
    public int[] previousStop;

    /**
     * For each stop, if the stop is optimally reached via a transfer, the stop from which that transfer originates.
     * If the stop is optimally reached by transit, or hasn't been reached at all, the value is -1.
     */
    public int[] transferStop;

    /**
     * This RaptorState will only store information about trips shorter than this duration. Travel times at or above
     * this limit will be treated as if the stop could not be reached at all.
     */
    public int maxDurationSeconds;

    /**
     * A set of all the stops whose arrival times were improved in this round, in the current raptor search in progress.
     * Note that in range-raptor, when reusing state from a later departure minute, arrival times may be earlier at
     * a particular stop than in the previous round due to those already-finished searches. That fact can easily be
     * detected by looking at the times themselves. Here we are separately tracking the updates made on top of those
     * upper bound times by this single-departure-minute (or single-randomized-schedule) search in progress.
     */
    public final BitSet stopsUpdated = new BitSet();

    /**
     * Same as stopsUpdated, but before transfers are applied.
     * This is mostly needed to prevent concurrent iteration and modification of the set when evaluating transfers.
     */
    public final BitSet nonTransferStopsUpdated = new BitSet();

    /**
     * Create a RaptorState for a network with a particular number of stops, and a given maximum travel duration.
     * Travel times to all stops are initialized to UNREACHED, which will be improved upon by the search process.
     * The previous round field is left null and should be set as needed by the code calling this constructor.
     */
    public RaptorState (int nStops, int maxDurationSeconds) {
        this.maxDurationSeconds = maxDurationSeconds;

        // Array slot for every stop is initialized to the maximum integer value, which the search will improve upon.
        this.bestTimes = newIntArray(nStops, UNREACHED);
        this.bestNonTransferTimes = newIntArray(nStops, UNREACHED);

        // Initialized to contain all -1, indicating "none".
        this.previousPatterns = newIntArray(nStops, -1);
        this.previousStop = newIntArray(nStops, -1);
        this.transferStop = newIntArray(nStops, -1);

        // These fields accumulate times, so are initially filled with zeros.
        this.nonTransferWaitTime = new int[nStops];
        this.nonTransferInVehicleTravelTime = new int[nStops];

        // Previous round reference should be set as needed by the code calling this constructor.
        this.previous = null;
    }

    /**
     * Copy constructor for reusing search state from one minute to the next in a range raptor search.
     * This makes a deep copy of all fields, except the sets of stopsUpdated (which are cleared) and the reference
     * to the previous round's state (which should be set as needed by the caller).
     */
    private RaptorState (RaptorState state) {
        this.bestTimes = Arrays.copyOf(state.bestTimes, state.bestTimes.length);
        this.bestNonTransferTimes = Arrays.copyOf(state.bestNonTransferTimes, state.bestNonTransferTimes.length);
        this.previousPatterns = Arrays.copyOf(state.previousPatterns, state.previousPatterns.length);
        this.previousStop = Arrays.copyOf(state.previousStop, state.previousStop.length);
        this.transferStop = Arrays.copyOf(state.transferStop, state.transferStop.length);
        this.nonTransferWaitTime = Arrays.copyOf(state.nonTransferWaitTime, state.nonTransferWaitTime.length);
        this.nonTransferInVehicleTravelTime =
                Arrays.copyOf(state.nonTransferInVehicleTravelTime, state.nonTransferInVehicleTravelTime.length);
        this.departureTime = state.departureTime;
        this.maxDurationSeconds = state.maxDurationSeconds;

        // As a failsafe, do not copy previous-round reference.
        // When creating new state chains, this reference must always change to a new state object.
        this.previous = null;
    }

    /**
     * Makes a deep copy of this raptor state. Everything is replicated, except the sets of stops updated in this round
     * (which are cleared) and the reference to the previous round's state (which is nulled out).
     */
    public RaptorState copy () {
        return new RaptorState(this);
    }

    /**
     * Merge the other state into this one, keeping the element-wise minimum travel times. Travel time components and
     * path information associated with these minimum elements is retained. This is useful for the range-raptor
     * optimization, where rather than beginning a round from scratch, we are trying to improve upon an existing round
     * from a search run at a later departure time.
     *
     * This is used to progress to a new search round, so it does not affect the sets of updated stops, which should be
     * cleared at the beginning of each new round so as to track only newly updated stops. Those stop sets are currently
     * cleared in advanceScheduledSearchToPreviousMinute but it could be clearer to also ensure they are empty here.
     *
     * Note that this effectively copies the full state at each stop from one round to the next. If a stop is not
     * updated in a particular round, the information about how it was reached optimally remains a replica of data from
     * a previous round. This can happen for several rounds in a row. To accurately retrace a path back through
     * transfers and transit rides, one may need to step back more than one round at the same stop to find the round
     * where the optimal value was established (see the Path constructor).
     */
    public void minMergePrevious () {
        checkNotNull(previous, "Merge may only be called on a state with a previous round.");
        checkArgument(previous.departureTime == this.departureTime,
                "Previous round should always have the same departure minute.");
        int nStops = this.bestTimes.length;
        for (int stop = 0; stop < nStops; stop++) {
            // When breaking a tie, prefer times from the previous round with fewer transfers.
            if (previous.bestTimes[stop] <= this.bestTimes[stop]) {
                this.bestTimes[stop] = previous.bestTimes[stop];
                this.transferStop[stop] = previous.transferStop[stop];
            }
            if (previous.bestNonTransferTimes[stop] <= this.bestNonTransferTimes[stop]) {
                this.bestNonTransferTimes[stop] = previous.bestNonTransferTimes[stop];
                this.previousPatterns[stop] = previous.previousPatterns[stop];
                this.previousStop[stop] = previous.previousStop[stop];
                this.nonTransferInVehicleTravelTime[stop] = previous.nonTransferInVehicleTravelTime[stop];
                this.nonTransferWaitTime[stop] = previous.nonTransferWaitTime[stop];
            }
        }
    }

    /**
     * Check a time against the best known times at a transit stop, and record the new time if it is optimal.
     * This same method is used to handle both transit arrivals and transfers, according to the transfer parameter.
     * When transfer is false, times can update both the bestNonTransferTime and the bestTime; when transfer is true,
     * only bestTimes can be updated.
     *
     * @param transfer if true, we are recording a time obtained via a transfer or the initial access leg in round 0
     * @return true if the new time was optimal and the state was updated, false if the existing values were better
     */
    public boolean setTimeAtStop(int stop, int time, int fromPattern, int fromStop, int waitTime, int inVehicleTime, boolean transfer) {
        // First check whether the supplied travel time exceeds the specified maximum for this search.
        if (time >= departureTime + maxDurationSeconds) {
            return false;
        }
        // Method return value: was the new time optimal, leading to a state update?
        boolean optimal = false;

        // If this is "not a transfer" it is a transit arrival. If it is better than any known transit arrival,
        // update the non-transfer time and path information, then consider updating the bestTimes.
        // We may want to consider splitting the post-transfer updating out into its own method to make this clearer.
        if (!transfer && time < bestNonTransferTimes[stop]) {
            bestNonTransferTimes[stop] = time;
            previousPatterns[stop] = fromPattern;
            previousStop[stop] = fromStop;

            // Carry the travel time components (wait and in-vehicle time) from the previous leg and increment them.
            int totalWaitTime, totalInVehicleTime;
            if (previous == null) {
                // first round, there is no previous wait time or in vehicle time
                // TODO how and when can this happen? Round zero contains only the access leg and has no transit.
                totalWaitTime = waitTime;
                totalInVehicleTime = inVehicleTime;
            } else {
                // TODO it seems like this whole block and the assignment below can be condensed significantly.
                if (previous.transferStop[fromStop] != -1) {
                    // The fromSop was optimally reached via a transfer at the end of the previous round.
                    // Get the wait and in-vehicle time from the source stop of that transfer.
                    int preTransferStop = previous.transferStop[fromStop];
                    totalWaitTime = previous.nonTransferWaitTime[preTransferStop] + waitTime;
                    totalInVehicleTime = previous.nonTransferInVehicleTravelTime[preTransferStop] + inVehicleTime;
                } else {
                    // The stop we boarded at was reached directly by transit in the previous round.
                    totalWaitTime = previous.nonTransferWaitTime[fromStop] + waitTime;
                    totalInVehicleTime = previous.nonTransferInVehicleTravelTime[fromStop] + inVehicleTime;
                }
            }
            nonTransferWaitTime[stop] = totalWaitTime;
            nonTransferInVehicleTravelTime[stop] = totalInVehicleTime;

            checkState(totalInVehicleTime + totalWaitTime <= (time - departureTime),
                    "Components of travel time are greater than total travel time.");

            optimal = true;
            nonTransferStopsUpdated.set(stop);
        }

        // At a given stop, bestTimes is always less than or equal to bestNonTransferTimes. It will always be equal to
        // the bestNonTransferTimes unless a transfer from some other stop yields an earlier time.
        // If bestTimes is updated due to a transit arrival, the travel time components are already updated by the
        // transit-handling block above. If it's due to a transfer, the travel time components were already recorded
        // by an optimal arrival at the source station of the transfer.
        if (time < bestTimes[stop]) {
            bestTimes[stop] = time;
            if (transfer) {
                transferStop[stop] = fromStop;
            } else {
                transferStop[stop] = -1;
            }
            optimal = true;
            stopsUpdated.set(stop);
        }
        return optimal;
    }

    /** Debug function: dump the path to a particular stop as a String. */
    public String dump (int stop) {
        Path p = new Path(this, stop);

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < p.length; i++) {
            sb.append(String.format("Stop %5d at %5d, reached by pattern %5d from stop %5d\n", p.alightStops[i], p.alightTimes[i], p.patterns[i], p.boardStops[i]));
        }

        return sb.toString();
    }

    /**
     * This is the core of the range-raptor optimization. We move the departure time back one or more minutes,
     * reusing the existing results from the later minute. They are all still valid trips, you just have to wait a
     * little longer to board them. Only when you move the departure time back far enough to catch an earlier vehicle
     * will the downstream results change. This also clears the reached stops sets in preparation for a new round.
     */
    public void setDepartureTime(int departureTime) {
        final int additionalWaitSeconds = this.departureTime - departureTime;
        // In current usage, we always decrement by one minute. McRaptor steps by different numbers of minutes but uses
        // a separate code path, and in fact does not apply the range raptor optimization.
        checkState(additionalWaitSeconds == 60, "Departure times may only be decremented by one minute.");
        this.departureTime = departureTime;
        stopsUpdated.clear();
        nonTransferStopsUpdated.clear();
        // Remove trips that exceed the maximum trip duration when the rider departs earlier (due to more wait time).
        // This whole loop does not seem strictly necessary. In testing, removing it does not change results since
        // real travel times and INF can both compare greater than a cutoff. In fact multi-cutoff depends on this being
        // true. Clearing these could have some performance impact though, avoiding scanning some routes.
        if (ENABLE_OPTIMIZATION_CLEAR_LONG_PATHS) {
            int maxClockTime = departureTime + maxDurationSeconds;
            for (int i = 0; i < bestTimes.length; i++) {
                if (bestTimes[i] >= maxClockTime) {
                    bestTimes[i] = UNREACHED;
                    transferStop[i] = -1;
                }
                if (bestNonTransferTimes[i] >= maxClockTime) {
                    bestNonTransferTimes[i] = UNREACHED;
                    // These were not being set before - they might not be necessary but at least it's clearer to set them.
                    previousPatterns[i] = -1;
                    previousStop[i] = -1;
                }
            }
        }
        // Update waiting times for all remaining trips, to reflect additional waiting time at first boarding.
        for (int stop = 0; stop < this.bestTimes.length; stop++) {
            if (this.previousPatterns[stop] > -1) {
                this.nonTransferWaitTime[stop] += additionalWaitSeconds;
            } else {
                this.nonTransferWaitTime[stop] = 0;
                this.nonTransferInVehicleTravelTime[stop] = 0;
            }
        }
    }

    /**
     * @param withinMinute if true, use the bitsets for the current minute, otherwise look at effects of all minutes.
     * @return whether the time was updated (with or without transfer) in the round represented by this state.
     */
    public boolean stopWasUpdated (int stop, boolean withinMinute) {
        if (withinMinute) {
            return stopsUpdated.get(stop);
        } else {
            int time = this.bestTimes[stop];
            int prevTime = (previous != null) ? previous.bestTimes[stop] : UNREACHED;
            return time < prevTime;
        }
    }

}
