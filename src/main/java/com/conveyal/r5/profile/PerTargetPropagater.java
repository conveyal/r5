package com.conveyal.r5.profile;

import com.conveyal.r5.streets.LinkedPointSet;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * This class propagates from times at transit stops to times at destinations (targets). It is called with a function
 * that will be called with the target index (which is the row-major 1D index of the destination that is being
 * propagated to) and the array of whether the target was reached within the travel time cutoff in each Monte Carlo
 * draw. This is used in GridComputer to perform bootstrapping of accessibility given median travel time. This function
 * is only called for targets that were ever reached. It may seem needlessly generic to use a lambda function, but it
 * allows us to confine the bootstrapping code to GridComputer.
 *
 * We propagate to a single target (grid cell) at a time because we only intend to store a few percentiles of travel time
 * at each target. Propagating the other direction, from stops out to targets, requires storing every travel time for
 * every MC draw/departure time, at every target. This would be impractically huge. To handle one target at a time we
 * need to invert the table of distances from stops to nearby targets: we instead use a table of distances from targets
 * to nearby stops.
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

    /**
     * A reducer is only told whether the target was reached within the travel time threshold, which allows some
     * optimizations in certain cases. A travelTimeReducer receives a full list of travel times to the given
     * destination.
     * TODO change function signature so this returns the resulting grid object
     */
    public void propagate (TravelTimeReducer travelTimeReducer) {
        targets.makePointToStopDistanceTablesIfNeeded();

        long startTimeMillis = System.currentTimeMillis();
        // avoid float math in loop below
        // float math was previously observed to slow down this loop, however it's debatable whether that was due to
        // casts, the operations themselves, or the fact that the operations were being completed with doubles rather
        // than floats.
        int speedMillimetersPerSecond = (int) (request.walkSpeed * 1000);

        int[] perIterationTravelTimes = new int[travelTimesToStopsEachIteration.length];

        // Invert the travel times to stops array, to provide better memory locality in the tight loop below. Confirmed
        // that this provides a significant speedup, which makes sense; Java doesn't have true multidimensional arrays
        // but rather represents int[][] as Object[int[]], which means that each of the arrays "on the inside" is stored
        // separately in memory and may not be contiguous, also meaning the CPU can't efficiently predict and prefetch
        // what we need next. When we invert the array, we then have all travel times to a particular stop for all iterations
        // in a single array. The CPU will only page the data that is relevant for the current target, i.e. the travel
        // times to nearby stops. Since we are also looping over the targets in a geographic manner (in row-major order),
        // it is likely the stops relevant to a particular target will already be in memory from the previous target.
        // This should not increase memory consumption very much as we're only storing the travel times to stops. The
        // Netherlands has about 70,000 stops, if you do 1,000 iterations to 70,000 stops, the array being duplicated is
        // only 70,000 * 1000 * 4 bytes per int ~= 267 megabytes. I don't think it will be worthwhile to change the
        // algorithm to output already-transposed data as that will create other memory locality problems (since the
        // pathfinding algorithm solves one iteration for all stops simultaneously).
        int[][] invertedTravelTimesToStops = new int[travelTimesToStopsEachIteration[0].length][travelTimesToStopsEachIteration.length];

        for (int iteration = 0; iteration < travelTimesToStopsEachIteration.length; iteration++) {
            for (int stop = 0; stop < travelTimesToStopsEachIteration[0].length; stop++) {
                invertedTravelTimesToStops[stop][iteration] = travelTimesToStopsEachIteration[iteration][stop];
            }
        }

        for (int targetIdx = 0; targetIdx < targets.size(); targetIdx++) {
            // clear previous results, fill with whether target is reached within the cutoff without transit (which does
            // not vary with monte carlo draw)
            Arrays.fill(perIterationTravelTimes, nonTransitTravelTimesToTargets[targetIdx]);

            TIntIntMap pointToStopDistanceTable = targets.pointToStopDistanceTables.get(targetIdx);

            // don't try to propagate transit if there are no nearby transit stops,
            // but still call the reducer below with the non-transit times, because you can walk even where there is no
            // transit
            if (pointToStopDistanceTable != null) {
                pointToStopDistanceTable.forEachEntry((stop, distanceMillimeters) -> {
                    for (int iteration = 0; iteration < perIterationTravelTimes.length; iteration++) {
                        int timeAtStop = invertedTravelTimesToStops[stop][iteration];

                        if (timeAtStop > cutoffSeconds || timeAtStop > perIterationTravelTimes[iteration]) continue; // avoid overflow

                        int timeAtTargetThisStop = timeAtStop + distanceMillimeters / speedMillimetersPerSecond;

                        if (timeAtTargetThisStop < cutoffSeconds) {
                            if (timeAtTargetThisStop < perIterationTravelTimes[iteration]) {
                                perIterationTravelTimes[iteration] = timeAtTargetThisStop;
                            }
                        }
                    }
                    return true;
                });
            }

            travelTimeReducer.recordTravelTimesForTarget(targetIdx, perIterationTravelTimes);
        }

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOG.info("Propagating {} iterations from {} stops to {} targets took {}s",
                travelTimesToStopsEachIteration.length,
                travelTimesToStopsEachIteration[0].length,
                targets.size(),
                totalTimeMillis / 1000d
                );

        travelTimeReducer.finish();
    }

    /**
     * This interface has two different implementations, one for creating an accessibility indicator and another for
     * creating a travel time surface. It receives a large number of travel time observations and retains
     * only a smaller set of summary measures.
     *
     * TODO our code always passes the targets into this interface in order, one after another, no need to buffer results.
     * We could just stream results out immediately.
     */
    public interface TravelTimeReducer {

        /**
         * Records a set of travel times (in seconds) to a particular target. Each travel time in the set represents
         * a different departure time or Monte Carlo schedule draw.
         * This should be called once per target, or not at all if we know nothing is accessible.
         */
        void recordTravelTimesForTarget(int targetIndex, int[] travelTimesForTarget);

        /**
         * Called when propagation is done, used to signal the reducer that it can write / upload its results to s3 etc.
         * If this is called immediately without supplying any travel times via recordTravelTimesForTarget,
         * we have bypassed propagation entirely and the implementation should write out a default result for cases
         * where the network is entirely unreachable.
         */
        default void finish () {};

    }
}
