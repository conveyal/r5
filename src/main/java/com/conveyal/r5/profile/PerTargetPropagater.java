package com.conveyal.r5.profile;

import com.conveyal.r5.streets.LinkedPointSet;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * This is similar to the Propagater class, but instead of the reducer function being called with each iteration,
 * it is called with the travel times to a particular target for all iterations.
 */
public class PerTargetPropagater {
    private static final Logger LOG = LoggerFactory.getLogger(PerTargetPropagater.class);

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

    public PerTargetPropagater (int[][] travelTimesToStopsEachIteration, int[] nonTransferTravelTimesToTargets, LinkedPointSet targets, ProfileRequest request, int cutoffSeconds) {
        this.travelTimesToStopsEachIteration = travelTimesToStopsEachIteration;
        this.nonTransferTravelTimesToTargets = nonTransferTravelTimesToTargets;
        this.targets = targets;
        this.request = request;
        this.cutoffSeconds = cutoffSeconds;
    }

    public void propagate (Reducer reducer) {
        targets.makePointToStopDistanceTablesIfNeeded();

        long startTimeMillis = System.currentTimeMillis();
        long timeSpentInReducerNanos = 0;
        // avoid float math in loop below
        // float math was previously observed to slow down this loop, however it's debatable whether that was due to
        // casts, the operations themselves, or the fact that the operations were being completed with doubles rather
        // than floats.
        int speedMillimetersPerSecond = (int) (request.walkSpeed * 1000);

        for (int targetIdx = 0; targetIdx < targets.size(); targetIdx++) {
            int[] perIterationResults = new int[travelTimesToStopsEachIteration.length];
            // fill with non-transit time as upper bound on travel time
            Arrays.fill(perIterationResults, nonTransferTravelTimesToTargets[targetIdx]);

            TIntIntMap pointToStopDistanceTable = targets.pointToStopDistanceTables.get(targetIdx);

            // don't try to propagate transit if there are no nearby transit stops,
            // but still call the reducer below with the non-transit times, because you can walk even where there is no
            // transit
            if (pointToStopDistanceTable != null) {
                for (int iteration = 0; iteration < perIterationResults.length; iteration++) {
                    final int effectivelyFinalIteration = iteration;
                    pointToStopDistanceTable.forEachEntry((stop, distanceMm) -> {
                        int timeAtStop = travelTimesToStopsEachIteration[effectivelyFinalIteration][stop];

                        if (timeAtStop == RaptorWorker.UNREACHED) return true; // avoid overflow

                        int timeAtTargetThisStop = timeAtStop + distanceMm / speedMillimetersPerSecond;

                        if (timeAtTargetThisStop < perIterationResults[effectivelyFinalIteration]) {
                            perIterationResults[effectivelyFinalIteration] = timeAtTargetThisStop;
                        }

                        return true; // continue iteration
                    });
                }
            }

            long reducerStartTime = System.nanoTime();
            reducer.accept(targetIdx, perIterationResults);
            timeSpentInReducerNanos += System.nanoTime() - reducerStartTime;
        }

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOG.info("Propagating {} iterations from {} stops to {} targets took {}s",
                travelTimesToStopsEachIteration.length,
                travelTimesToStopsEachIteration[0].length,
                targets.size(),
                totalTimeMillis / 1000d
                );
        LOG.info(" - {}s in propagation", totalTimeMillis / 1000d - timeSpentInReducerNanos / 1e6);
        LOG.info(" - {}s in reducer", timeSpentInReducerNanos / 1e6);
    }

    public static interface Reducer {
        public void accept (int targetIndex, int[] travelTimesForTarget);
    }
}
