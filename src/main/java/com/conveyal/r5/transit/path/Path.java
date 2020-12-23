package com.conveyal.r5.transit.path;

import com.conveyal.r5.profile.RaptorState;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkState;

/**
 *
 */
public class Path implements Cloneable {

    private static final Logger LOG = LoggerFactory.getLogger(Path.class);

    public PathTemplate pathTemplate;
    public final int departureTime;
    public final int waitTime;
    public int initialWait;

    /**
     * Extract the path leading up to a specified stop in a given raptor state.
     */
    public Path(RaptorState state, int stop) {

        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList cumulativeInVehicleTimes = new TIntArrayList();

        this.departureTime = state.departureTime;
        this.waitTime = state.nonTransferWaitTime[stop];

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

            cumulativeInVehicleTimes.add(state.nonTransferInVehicleTravelTime[stop]);
            alightStops.add(stop);
            stop = state.previousStop[stop];
            boardStops.add(stop);

            this.initialWait = state.nonTransferWaitTime[stop];
            // go to previous state before handling transfers as transfers are done at the end of a round
            state = state.previous;

            // handle transfers
            if (state.transferStop[stop] != -1) {
                stop = state.transferStop[stop];
            }
        }

        int length = patterns.size();
        if (length == 0)
            LOG.error("Transit path computed without a transit segment!");
        pathTemplate = new PathTemplate(length);

        // we traversed up the tree but the user wants to see paths down the tree
        // TODO when we do reverse searches we won't want to reverse paths
        patterns.reverse();
        boardStops.reverse();
        alightStops.reverse();

        pathTemplate.patterns = patterns.toArray();
        pathTemplate.boardStops = boardStops.toArray();
        pathTemplate.alightStops = alightStops.toArray();

        for (int i = 0; i < cumulativeInVehicleTimes.size() - 1; i++) {
            int inVehicleTime = cumulativeInVehicleTimes.get(i) - cumulativeInVehicleTimes.get(i + 1);
            pathTemplate.rideTimesSeconds[length - 1 - i] = inVehicleTime;
        }

        pathTemplate.rideTimesSeconds[0] = cumulativeInVehicleTimes.get(length - 1);

    }

    @Override
    public Path clone() {
        try {
            return (Path) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    public void setTransferTimeFromTotalTime(int totalTime) {
        checkState(pathTemplate.access != null);
        checkState(pathTemplate.egress != null);
        pathTemplate.transferTimeSeconds = totalTime - waitTime - pathTemplate.access.time - pathTemplate.egress.time - Arrays.stream(pathTemplate.rideTimesSeconds).sum();
        // checkState(pathTemplate.transferTimeSeconds >= 0);
    }
}
