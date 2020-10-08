package com.conveyal.r5.profile;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.google.common.primitives.Ints;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkState;

/**
 * Class used to represent transit paths for display to end users (and debugging).
 * It is a group of parallel arrays, with each position in the arrays representing a leg in the trip.
 */
public class Path {

    private static final Logger LOG = LoggerFactory.getLogger(Path.class);

    // FIXME assuming < 4 legs on each path, this parallel array implementation probably doesn't use less memory than a List<Leg>.
    // It does effectively allow you to leave out the boardStopPositions and alightStopPositions, but a subclass could do that too.
    public int[] patterns;
    public int[] boardStops;
    public int[] alightStops;
    public int[] alightTimes;
    public int[] trips;
    public int[] boardStopPositions;
    public int[] alightStopPositions;
    public final int length;

    /**
     * Extract the path leading up to a specified stop in a given raptor state.
     */
    public Path(RaptorState state, int stop) {

        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList times = new TIntArrayList();
        TIntList alightTimes = new TIntArrayList();

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
            alightStops.add(stop);
            times.add(state.bestTimes[stop]);
            alightTimes.add(state.bestNonTransferTimes[stop]);
            stop = state.previousStop[stop];
            boardStops.add(stop);

            // go to previous state before handling transfers as transfers are done at the end of a round
            state = state.previous;

            // handle transfers
            if (state.transferStop[stop] != -1) {
                stop = state.transferStop[stop];
            }
        }

        // we traversed up the tree but the user wants to see paths down the tree
        // TODO when we do reverse searches we won't want to reverse paths
        patterns.reverse();
        boardStops.reverse();
        alightStops.reverse();
        alightTimes.reverse();

        this.patterns = patterns.toArray();
        this.boardStops = boardStops.toArray();
        this.alightStops = alightStops.toArray();
        this.alightTimes = alightTimes.toArray();
        this.length = this.patterns.length;

        if (this.patterns.length == 0)
            LOG.error("Transit path computed without a transit segment!");
    }

    /**
     * Scan over a mcraptor state and extract the path leading up to that state.
     */
    public Path(McRaptorSuboptimalPathProfileRouter.McRaptorState s) {
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList alightTimes = new TIntArrayList();
        TIntList trips = new TIntArrayList();
        TIntList boardStopPositions = new TIntArrayList();
        TIntList alightStopPositions = new TIntArrayList();

        // trace path from this McRaptorState
        do {
            // skip transfers, they are implied
            if (s.pattern == -1) s = s.back;

            patterns.add(s.pattern);
            alightTimes.add(s.time);
            alightStops.add(s.stop);
            boardStops.add(s.back.stop);
            trips.add(s.trip);
            boardStopPositions.add(s.boardStopPosition);
            alightStopPositions.add(s.alightStopPosition);

            s = s.back;
        } while (s.back != null);

        patterns.reverse();
        boardStops.reverse();
        alightStops.reverse();
        alightTimes.reverse();
        trips.reverse();
        boardStopPositions.reverse();
        alightStopPositions.reverse();

        this.patterns = patterns.toArray();
        this.boardStops = boardStops.toArray();
        this.alightStops = alightStops.toArray();
        this.alightTimes = alightTimes.toArray();
        this.trips = trips.toArray();
        this.boardStopPositions = boardStopPositions.toArray();
        this.alightStopPositions = alightStopPositions.toArray();
        this.length = this.patterns.length;

        if (this.patterns.length == 0)
            LOG.error("Transit path computed without a transit segment!");
    }

    // The semantic HashCode and Equals are used in deduplicating the paths for static site output.
    // They will be calculated millions of times so might be slow with all these multiplications.

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Path path = (Path) o;
        return length == path.length &&
                Arrays.equals(patterns, path.patterns) &&
                Arrays.equals(boardStops, path.boardStops) &&
                Arrays.equals(alightStops, path.alightStops) &&
                Arrays.equals(alightTimes, path.alightTimes) &&
                Arrays.equals(trips, path.trips) &&
                Arrays.equals(boardStopPositions, path.boardStopPositions) &&
                Arrays.equals(alightStopPositions, path.alightStopPositions);
    }

    @Override
    public int hashCode() {
        int result = Ints.hashCode(length);
        result = 31 * result + Arrays.hashCode(patterns);
        result = 31 * result + Arrays.hashCode(boardStops);
        result = 31 * result + Arrays.hashCode(alightStops);
        result = 31 * result + Arrays.hashCode(alightTimes);
        result = 31 * result + Arrays.hashCode(trips);
        result = 31 * result + Arrays.hashCode(boardStopPositions);
        result = 31 * result + Arrays.hashCode(alightStopPositions);
        return result;
    }

    /**
     * Gets tripPattern at provided pathIndex
     */
    public TripPattern getPattern(TransitLayer transitLayer, int pathIndex) {
        return transitLayer.tripPatterns.get(this.patterns[pathIndex]);
    }

    @Override
    public String toString () {
        var builder = new StringBuilder();
        builder.append("Path:\n");
        for (int i = 0; i < length; i++) {
            builder.append("  from ");
            builder.append(boardStops[i]);
            builder.append(" to ");
            builder.append(alightStops[i]);
            builder.append(" riding ");
            builder.append(patterns[i]);
            builder.append(" alighting time ");
            builder.append(alightTimes[i]);
            builder.append("\n");
        }
        return builder.toString();
    }
}
