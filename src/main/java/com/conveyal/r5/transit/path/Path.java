package com.conveyal.r5.transit.path;

import com.conveyal.r5.profile.RaptorState;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkState;

/// A door-to-door transit itinerary as a sequence of specific vehicles boarded at specific clock times.
/// All times describing the transit legs are clock times rather than durations relative to a departure time.
/// This means one Path instance can describe the itinerary of a rider departing the origin at any minute for which
/// the itinerary remains optimal. Waiting times, including the initial wait implied by a particular departure time,
/// are derived on demand (see computeWaitTimes). These are optional results from Raptor searches.
public class Path {

    private static final Logger LOG = LoggerFactory.getLogger(Path.class);

    public final PatternSequence patternSequence;

    /// For each transit leg, the clock time at which the vehicle departs its boarding stop. Together with the fixed
    /// quantities in the patternSequence (ride times and transfer walk times), these determine all the itinerary's
    /// time components except the initial wait, which additionally depends on the departure time.
    public final TIntList boardTimes;

    /// Extract the path leading up to a specified stop in a given raptor state. All time components are derived from
    /// clock times recorded in the chain of raptor states. Subtraction of clock times observed in the same chain of
    /// states keeps the components consistent, even when range raptor has retained a ride recorded at a later departure
    /// minute (at an earlier departure minute, with a longer wait before boarding).
    public Path(RaptorState state, int stop) {

        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList boardTimes = new TIntArrayList();
        TIntList rideTimes = new TIntArrayList();

        // The on-street time spent transferring boarding each leg. This is zero for a leg boarded at the same
        // place the previous leg alighted, and for the first leg (the access leg is recorded separately).
        TIntList transferTimes = new TIntArrayList();

        while (state.previous != null) {
            // We copy the state at each stop from one round to the next. If a stop is not updated in a particular
            // round, the information about how it was reached optimally will be found in a previous round.
            // Step back through the rounds until we find a round where this stop was updated.
            if (state.previous.bestNonTransferTimes[stop] == state.bestNonTransferTimes[stop]) {
                state = state.previous;
                continue;
            }
            checkState(state.previous.bestNonTransferTimes[stop] >= state.bestNonTransferTimes[stop],
                    "Earlier raptor rounds must have later arrival times at a given stop.");

            // Record details of the transit leg just ridden.
            int alightTime = state.bestNonTransferTimes[stop];
            int boardTime = state.previousBoardTime[stop];
            patterns.add(state.previousPatterns[stop]);
            alightStops.add(stop);
            boardTimes.add(boardTime);
            rideTimes.add(alightTime - boardTime);

            // Step back to boarding stop
            stop = state.previousStop[stop];
            boardStops.add(stop);

            // Step back to previous state before handling transfers, as transfers are done at the end of a round
            state = state.previous;

            // Record the duration of any transfer to reach the boarding just recorded
            if (state.transferStop[stop] != -1) {
                int transferOrigin = state.transferStop[stop];
                transferTimes.add(state.bestTimes[stop] - state.bestNonTransferTimes[transferOrigin]);
                stop = transferOrigin;
            } else {
                transferTimes.add(0);
            }
        }

        int length = patterns.size();
        if (length == 0)
            LOG.error("Transit path computed without a transit segment!");

        // We traversed up the tree (working backward in time) but the user wants to see paths down the tree
        // TODO when we do reverse searches we won't want to reverse paths
        patterns.reverse();
        boardStops.reverse();
        alightStops.reverse();
        boardTimes.reverse();
        rideTimes.reverse();
        transferTimes.reverse();

        this.boardTimes = boardTimes;
        patternSequence = new PatternSequence(patterns, boardStops, alightStops, rideTimes, transferTimes);
    }

    /// Derive the wait before boarding each transit leg, for a rider leaving the origin at the given departure time.
    /// The rider is ready to board the first vehicle after finishing the access leg, and ready to board each later
    /// vehicle after alighting from the previous one and finishing any transfer walk. The wait is the time between
    /// being ready to board a vehicle and its recorded departure. The access leg must already be set on this path's
    /// stop sequence (see StopSequence#setAccess).
    public TIntList computeWaitTimes (int departureTime) {
        StopSequence stopSequence = patternSequence.stopSequence;
        checkState(stopSequence.access != null, "Waiting times can only be derived once the access leg is set.");
        TIntList waitTimes = new TIntArrayList(boardTimes.size());
        int readyToBoard = departureTime + stopSequence.access.time;
        for (int leg = 0; leg < boardTimes.size(); leg++) {
            readyToBoard += stopSequence.transferTimesSeconds.get(leg);
            int waitTime = boardTimes.get(leg) - readyToBoard;
            checkState(waitTime >= 0, "Derived a negative wait time, which indicates an infeasible itinerary.");
            waitTimes.add(waitTime);
            readyToBoard = boardTimes.get(leg) + stopSequence.rideTimesSeconds.get(leg);
        }
        return waitTimes;
    }
}
