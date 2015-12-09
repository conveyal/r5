package com.conveyal.r5.profile;

import com.conveyal.r5.publish.StaticComputer;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Created by matthewc on 12/9/15.
 */
public class Path {
    private static final Logger LOG = LoggerFactory.getLogger(Path.class);

    public int[] patterns;
    public int[] boardStops;
    public int[] alightStops;
    public int[] alightTimes;
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

    public int hashCode() {
        return Arrays.hashCode(patterns) + 2 * Arrays.hashCode(boardStops) + 5 * Arrays.hashCode(alightStops);
    }

    public boolean equals(Object o) {
        if (o instanceof Path) {
            Path p = (Path) o;
            return this == p || Arrays.equals(patterns, p.patterns) && Arrays.equals(boardStops, p.boardStops) && Arrays.equals(alightStops, p.alightStops);
        } else return false;
    }
}
