package com.conveyal.r5.profile;

import com.conveyal.r5.streets.LinkedPointSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

/**
 * A propagater does propagation, like how an alligator does allegation.
 *
 * This takes a matrix of times at each transit stop for each RAPTOR iteration, and propagates those times
 * to the street network. A matrix of travel times to points at each iteration can be too large in very large
 * graphs (e.g. NYC or NL), thus this works in a streaming fashion, calling a function passed into the propagate
 * call with the results of each iteration, which can then be summarized or written to disk as the caller desires.
 */
public class Propagater {
    private static final Logger LOG = LoggerFactory.getLogger(Propagater.class);

    /** Times at transit stops for each iteration */
    public final int[][] travelTimesToStopsEachIteration;

    /** Times at targets using the street network */
    public final int[] nonTransferTravelTimesToTargets;

    /** The maximum allowable travel time in this regional analysis, to avoid propagating things that won't be useful */
    public final int cutoffSeconds;

    /** The linked targets */
    public final LinkedPointSet targets;

    /** the profilerequest (used for walk speed etc.) */
    public final ProfileRequest request;

    public Propagater (int[][] travelTimesToStopsEachIteration, int[] nonTransferTravelTimesToTargets, LinkedPointSet targets, ProfileRequest request, int cutoffSeconds) {
        this.travelTimesToStopsEachIteration = travelTimesToStopsEachIteration;
        this.nonTransferTravelTimesToTargets = nonTransferTravelTimesToTargets;
        this.targets = targets;
        this.request = request;
        this.cutoffSeconds = cutoffSeconds;
    }

    /**
     * perform the propagation from transit stops to targets, and call the reducer with the travel time to each target
     * for each iteration.
     *
     * For large networks, making a 2-D array of all the travel times to every target is too large to fit in memory, so
     * we call the reducer function with the times for each iteration, and it can summarize on the fly, write to disk,
     * &c. as desired by the caller.
     */
    public int[] propagate (ToIntFunction<int[]> reducer) {
        long startTime = System.currentTimeMillis();
        // propagate the transit times
        // NB if this is slow, parallelize; however, it's observed to be quick even in large networks, and for regional
        // analyses, the entire request is already parallelized at the origin level (i.e. we're computing the accessibility
        // for multiple origins simultaneously).
        // This can use something akin to range-RAPTOR where we only update the travel time if it changes from one
        // iteration to the next, but that requires working on clock times, so we'd have to pass more information down
        // into this function; I'm not sure it's worth it.
        int[] result = new int[travelTimesToStopsEachIteration.length];
        int[] travelTimesToTargetsThisIteration = new int[nonTransferTravelTimesToTargets.length];
        for (int iteration = 0; iteration < travelTimesToStopsEachIteration.length; iteration++) {
            for (int i = 0; i < travelTimesToTargetsThisIteration.length; i++) {
                travelTimesToTargetsThisIteration[i] = nonTransferTravelTimesToTargets[i];
            }

            int[] travelTimesToStopsThisIteration = travelTimesToStopsEachIteration[iteration];

            // avoid float math in loop below
            // float math was previously observed to slow down this loop, however it's debatable whether that was due to
            // casts, the operations themselves, or the fact that the operations were being completed with doubles rather
            // than floats.
            int speedMillimetersPerSecond = (int) (request.walkSpeed * 1000);

            // Loop over each stop and propagate
            for (int stop = 0; stop < travelTimesToStopsThisIteration.length; stop++) {
                int travelTimeToStop = travelTimesToStopsThisIteration[stop];

                // don't bother to even propagate stops that take too long to get to
                // this will also skip unreached stops
                if (travelTimeToStop > cutoffSeconds) continue;

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
