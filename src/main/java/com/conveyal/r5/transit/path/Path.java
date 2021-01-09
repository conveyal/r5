package com.conveyal.r5.transit.path;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.conveyal.r5.profile.RaptorState;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static com.google.common.base.Preconditions.checkState;

/**
 * Fully specified door-to-door itinerary (at a specific departure time and with specific wait times, which may be
 * derived from random frequency offsets). These itineraries are an optional result from Raptor searches, and they
 * can be reduced/summarized in different ways (e.g. de-duplicating repeated PatternSequences or RouteSequences)
 * TODO rename to Itinerary, compare to PathWithTimes?
 */
public class Path implements Cloneable {

    private static final Logger LOG = LoggerFactory.getLogger(Path.class);

    public final PatternSequence patternSequence;
    public final int departureTime;
    // One wait time for each transit leg boarded. This is tracked outside the pathTemplate, because initial wait
    // will depends on the specific departure time (and subsequent waits may depend on random frequency offsets used
    // in the Monte Carlo approach).
    public final TIntList waitTimes;

    /**
     * Extract the path leading up to a specified stop in a given raptor state.
     */
    public Path(RaptorState state, int stop) {

        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList waitTimes = new TIntArrayList();
        TIntList inVehicleTimes = new TIntArrayList();

        this.departureTime = state.departureTime;

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
            patterns.add(state.previousPatterns[stop]);

            // Set details of the transit leg just ridden.
            alightStops.add(stop);
            waitTimes.add(state.previousWaitTime[stop]);
            inVehicleTimes.add(state.previousInVehicleTravelTime[stop]);

            // Step back to boarding stop
            stop = state.previousStop[stop];
            boardStops.add(stop);

            // Step back to previous state before handling transfers, as transfers are done at the end of a round
            state = state.previous;

            // handle transfers
            if (state.transferStop[stop] != -1) {
                stop = state.transferStop[stop];
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
        waitTimes.reverse();
        inVehicleTimes.reverse();

        this.waitTimes = waitTimes;
        patternSequence = new PatternSequence(patterns, boardStops, alightStops, inVehicleTimes);
    }

    @Override
    public Path clone() {
        try {
            return (Path) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}
