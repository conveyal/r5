package com.conveyal.r5.profile;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Class used to represent paths in Browsochrones.
 */
public class Path {
    private static final Logger LOG = LoggerFactory.getLogger(Path.class);

    public int[] patterns;
    public int[] boardStops;
    public int[] alightStops;
    public int[] alightTimes;
    public int[] trips;
    public int[] boardStopPositions;
    public int[] alightStopPositions;
    public final int length;

    public Path(RaptorState state, int stop) {
        // trace the path back from this RaptorState
        int previousPattern;
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList times = new TIntArrayList();
        TIntList alightTimes = new TIntArrayList();

        boolean first = true;

        while (state.previous != null) {
            // find the fewest-transfers trip that is still optimal in terms of travel time
            if (state.previous.bestNonTransferTimes[stop] == state.bestNonTransferTimes[stop]) {
                state = state.previous;
                continue;
            }

            if (state.previous.bestNonTransferTimes[stop] < state.bestNonTransferTimes[stop]) {
                LOG.error("Previous round has lower weight at stop {}, this implies a bug!", stop);
            }

            previousPattern = state.previousPatterns[stop];

            patterns.add(previousPattern);
            alightStops.add(stop);
            times.add(state.bestTimes[stop]);
            alightTimes.add(state.bestNonTransferTimes[stop]);
            stop = state.previousStop[stop];
            boardStops.add(stop);

            first = false;

            // go to previous state before handling transfers as transfers are done at the end of a round
            state = state.previous;

            // handle transfers
            if (state.transferStop[stop] != -1)
                stop = state.transferStop[stop];
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

    public int hashCode() {
        return Arrays.hashCode(patterns);
    }

    public boolean equals(Object o) {
        if (o instanceof Path) {
            Path p = (Path) o;
            return this == p || Arrays.equals(patterns, p.patterns);
        } else return false;
    }

    /**
     * Gets tripPattern at provided pathIndex
     * @param transitLayer
     * @param pathIndex
     * @return
     */
    public TripPattern getPattern(TransitLayer transitLayer, int pathIndex) {
        return transitLayer.tripPatterns.get(this.patterns[pathIndex]);
    }

}
