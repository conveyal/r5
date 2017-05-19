package com.conveyal.r5.profile;

import com.conveyal.r5.streets.LinkedPointSet;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * This class propagates from times at transit stops to times at destinations (targets). It is called with a function
 * that will be called with  the target index (which is the row-major 1D index of the destination that is being
 * propagated to) and the array of whether the target was reached within the travel time cutoff in each Monte Carlo
 * draw. This is used in GridComputer to perform bootstrapping of accessibility given median travel time. This function
 * is only called for targets that were ever reached.
 *
 * It may seem needlessly generic to use a lambda function, but it allows us to confine the bootstrapping code to GridComputer.
 * Perhaps this should be refactored to be a BootstrappingPropagater that just returns bootstrapped accessibility values.
 */
public class PerTargetPropagater {
    private static final Logger LOG = LoggerFactory.getLogger(PerTargetPropagater.class);

    /** Times at transit stops for each iteration */
    public final int[][] travelTimesToStopsEachIteration;

    /** Times at targets using the street network */
    public final int[] nonTransitTravelTimesToTargets;

    /** The travel time cutoff in this regional analysis */
    public final int cutoffSeconds;

    /** The linked targets */
    public final LinkedPointSet targets;

    /** the profilerequest (used for walk speed etc.) */
    public final ProfileRequest request;

    public PerTargetPropagater (int[][] travelTimesToStopsEachIteration, int[] nonTransitTravelTimesToTargets, LinkedPointSet targets, ProfileRequest request, int cutoffSeconds) {
        this.travelTimesToStopsEachIteration = travelTimesToStopsEachIteration;
        this.nonTransitTravelTimesToTargets = nonTransitTravelTimesToTargets;
        this.targets = targets;
        this.request = request;
        this.cutoffSeconds = cutoffSeconds;
    }

    private void propagate (Reducer reducer, TravelTimeReducer travelTimeReducer) {
        boolean saveTravelTimes = travelTimeReducer != null;
        targets.makePointToStopDistanceTablesIfNeeded();

        long startTimeMillis = System.currentTimeMillis();
        // avoid float math in loop below
        // float math was previously observed to slow down this loop, however it's debatable whether that was due to
        // casts, the operations themselves, or the fact that the operations were being completed with doubles rather
        // than floats.
        int speedMillimetersPerSecond = (int) (request.walkSpeed * 1000);

        boolean[] perIterationResults = new boolean[travelTimesToStopsEachIteration.length];
        int[] perIterationTravelTimes = saveTravelTimes ? new int[travelTimesToStopsEachIteration.length] : null;

        for (int targetIdx = 0; targetIdx < targets.size(); targetIdx++) {
            // clear previous results, fill with whether target is reached within the cutoff without transit (which does
            // not vary with monte carlo draw)
            boolean targetReachedWithoutTransit = nonTransitTravelTimesToTargets[targetIdx] < cutoffSeconds;
            Arrays.fill(perIterationResults, targetReachedWithoutTransit);
            if (saveTravelTimes) {
                Arrays.fill(perIterationTravelTimes, nonTransitTravelTimesToTargets[targetIdx]);
            }

            if (targetReachedWithoutTransit && !saveTravelTimes) {
                // if the target is reached without transit, there's no need to do any propagation as the array cannot
                // change
                reducer.accept(targetIdx, perIterationResults);
                continue;
            }

            TIntIntMap pointToStopDistanceTable = targets.pointToStopDistanceTables.get(targetIdx);

            // all variables used in lambdas must be "effectively final"; arrays are effectively final even if their
            // values change
            boolean[] targetEverReached = new boolean[] { nonTransitTravelTimesToTargets[targetIdx] <= cutoffSeconds };

            // don't try to propagate transit if there are no nearby transit stops,
            // but still call the reducer below with the non-transit times, because you can walk even where there is no
            // transit
            if (pointToStopDistanceTable != null) {
                for (int iteration = 0; iteration < perIterationResults.length; iteration++) {
                    final int effectivelyFinalIteration = iteration;
                    pointToStopDistanceTable.forEachEntry((stop, distanceMillimeters) -> {
                        int timeAtStop = travelTimesToStopsEachIteration[effectivelyFinalIteration][stop];

                        if (timeAtStop > cutoffSeconds) return true; // avoid overflow

                        int timeAtTargetThisStop = timeAtStop + distanceMillimeters / speedMillimetersPerSecond;

                        if (timeAtTargetThisStop < cutoffSeconds) {
                            if (saveTravelTimes) {
                                if (timeAtTargetThisStop < perIterationTravelTimes[effectivelyFinalIteration]) {
                                    perIterationTravelTimes[effectivelyFinalIteration] = timeAtTargetThisStop;
                                    targetEverReached[0] = true;
                                }
                                return true; // continue iteration over stops, another stop might provide a faster way to get here
                            } else {
                                perIterationResults[effectivelyFinalIteration] = true;
                                targetEverReached[0] = true;

                                // if this target is reached within the travel time cutoff from any stop, we don't need to
                                // continue propagation since we don't actually care about the travel time, just whether it
                                // was less than the cutoff.
                                return false; // stop iteration over stops
                            }
                        } else {
                            return true; // continue iteration
                        }
                    });
                }
            }

            if (saveTravelTimes) travelTimeReducer.accept(targetIdx, perIterationTravelTimes);
            else if (targetEverReached[0]) reducer.accept(targetIdx, perIterationResults);
        }

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOG.info("Propagating {} iterations from {} stops to {} targets took {}s",
                travelTimesToStopsEachIteration.length,
                travelTimesToStopsEachIteration[0].length,
                targets.size(),
                totalTimeMillis / 1000d
                );
    }

    public void propagate (Reducer reducer) {
        propagate(reducer, null);
    }

    public void propagateTimes (TravelTimeReducer reducer) {
        propagate(null, reducer);
    }

    public static interface Reducer {
        public void accept (int targetIndex, boolean[] targetReachedWithinCutoff);
    }

    public interface TravelTimeReducer {
        public void accept (int targetIndex, int[] travelTimesForTargets);
    }
}
