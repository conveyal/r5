package com.conveyal.r5.profile;

import com.conveyal.r5.streets.LinkedPointSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

/**
 * A propagater does propagation, like how an alligator does allegation.
 */
public class Propagater {
    private static final Logger LOG = LoggerFactory.getLogger(Propagater.class);

    /** Times at transit stops for each iteration */
    public final int[][] travelTimesToStopsEachIteration;

    /** Times at targets using the street network */
    public final int[] nonTransferTravelTimesToTargets;

    /** The linked targets */
    public final LinkedPointSet targets;

    /** the profilerequest (used for walk speed etc.) */
    public final ProfileRequest request;

    public Propagater (int[][] travelTimesToStopsEachIteration, int[] nonTransferTravelTimesToTargets, LinkedPointSet targets, ProfileRequest request) {
        this.travelTimesToStopsEachIteration = travelTimesToStopsEachIteration;
        this.nonTransferTravelTimesToTargets = nonTransferTravelTimesToTargets;
        this.targets = targets;
        this.request = request;
    }

    /**
     * perform the propagation, and call the reducer with the travel time to each target for each iteration.
     *
     * For large networks, making a 2-D array of all the travel times to every target is too large to fit in memory.
     */
    public int[] propagate (ToIntFunction<int[]> reducer) {
        long startTime = System.currentTimeMillis();
        // propagate the transit times
        // NB if this is slow, parallelize
        // This can use something akin to range-RAPTOR where we only update the travel time if it changes from one
        // iteration to the next, but that requires working on clock times, so we'd have to pass more information down
        // into this function; I'm not sure it's worth it.
        int[] result = new int[travelTimesToStopsEachIteration.length];
        for (int iteration = 0; iteration < travelTimesToStopsEachIteration.length; iteration++) {
            int[] travelTimesToTargetsThisIteration = Arrays.copyOf(nonTransferTravelTimesToTargets, nonTransferTravelTimesToTargets.length);
            int[] travelTimesToStopsThisIteration = travelTimesToStopsEachIteration[iteration];

            // avoid float math in loop below
            int speedMillimetersPerSecond = (int) (request.walkSpeed * 1000);

            // Loop over each stop and propagate
            for (int stop = 0; stop < travelTimesToStopsThisIteration.length; stop++) {
                int travelTimeToStop = travelTimesToStopsThisIteration[stop];

                if (travelTimeToStop == RaptorWorker.UNREACHED) continue;

                int[] stopToTargetTree = targets.stopToPointDistanceTables.get(stop);
                if (stopToTargetTree == null) continue; // stop is unlinked
                for (int idx = 0; idx < stopToTargetTree.length; idx += 2) {
                    int target = stopToTargetTree[idx];
                    int distanceMillimeters = stopToTargetTree[idx + 1];
                    int timeSeconds = distanceMillimeters / speedMillimetersPerSecond;
                    int timeToTargetIncludingTransit = travelTimeToStop + timeSeconds;

                    if (timeToTargetIncludingTransit < travelTimesToTargetsThisIteration[target]) {
                        travelTimesToTargetsThisIteration[target] = timeToTargetIncludingTransit;
                    }
                }
            }

            result[iteration] = reducer.applyAsInt(travelTimesToTargetsThisIteration);
        }

        LOG.info("Propagation from {} stops to {} targets for {} iterations took {}s",
                travelTimesToStopsEachIteration[0].length,
                targets.size(),
                travelTimesToStopsEachIteration.length,
                (System.currentTimeMillis() - startTime) / 1000d
                );

        return result;
    }
}
