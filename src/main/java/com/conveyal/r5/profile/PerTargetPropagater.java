package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.LinkageCache;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.StreetTimeAndMode;
import com.conveyal.r5.analyst.cluster.PathResultsRecorder;
import com.conveyal.r5.streets.EgressCostTable;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Given minimum travel times from a single origin point to all transit stops, this class finds minimum travel times to
 * a grid of destinations ("targets") by walking or biking or driving from the transit stops to the targets.
 * We make one propagator instance per origin point.
 *
 * We find travel times to a single target (grid cell) at a time because we only intend to retain a few percentiles of
 * travel time at each target, rather than retaining the travel time for every Monte Carlo schedule at every departure
 * minute. Propagating the other direction (from stops out to targets) requires retaining every travel time for every
 * Monte Carlo draw/departure time combination at every target. The resulting data structure would be impractically
 * huge. To handle one target at a time rather than one stop at a time, we need to invert the table of distances from
 * stops to their nearby targets: we instead use a table of distances to targets from their nearby stops.
 *
 * Note: this class is not threadsafe. It must process one target at a time sequentially in a single thread.
 */
public class PerTargetPropagater {
    private static final Logger LOG = LoggerFactory.getLogger(PerTargetPropagater.class);
    /**
     * Transpose the travel times to stops array in order to provide better memory locality in the tight loop below.
     * We have confirmed that this provides a significant speedup.
     * TODO quantify that speedup and record here in comment.
     *      This takes something like 800msec for a large 6000-iteration search
     * This speedup is expected because Java represents multidimensional arrays as an array of references to arrays.
     * This means that each row is stored in a separate chunk of address space, and may not be contiguous with
     * other rows. The CPU and cache probably can't efficiently predict and prefetch the values we need next.
     * When we transpose the array, we then have all travel times to a particular stop for all iterations
     * in a single contiguous array. This means we pull in all the travel times for a particular stop into cache, at
     * once, rather than unneeded times for the same iteration at other stops. Since we are also looping over the
     * targets with geographic locality (adjacent cells in the destination grid are geographically adjacent),
     * it is likely that the stops pulled into cache by handling one target will be reused when handling the next target.
     * This should not increase memory consumption very much as we're only duplicating the travel times to transit
     * stops. For example, the Netherlands has about 70,000 stops, if you do 1,000 iterations to 70,000 stops, the
     * array being transposed and duplicated is 70,000 * 1000 * 4 bytes per int ~= 267 megabytes. It does not seem
     * worthwhile to change the routing algorithm to output already-transposed data, as that will create memory
     * locality problems elsewhere (since the pathfinding algorithm solves one iteration for all stops simultaneously).
     */
    public static int[][] getInvertedTravelTimesToStops(
            int[][] travelTimes
    ) {
        int nIterations = travelTimes.length;
        int nStops = travelTimes[0].length;
        int[][] invertedTimes = new int[nStops][nIterations];
        for (int iteration = 0; iteration < nIterations; iteration++) {
            for (int stopIdx = 0; stopIdx < nStops; stopIdx++) {
                invertedTimes[stopIdx][iteration] = travelTimes[iteration][stopIdx];
            }
        }
        return invertedTimes;
    }

    public static List<EgressCostTable> getTransposedEgressCostTables(
            PointSet targets,
            EnumSet<StreetMode> modes,
            StreetLayer streetLayer,
            LinkageCache linkageCache
    ) {
        List<EgressCostTable> egressCostTables = new ArrayList<>();
        for (StreetMode streetMode : modes) {
            LinkedPointSet linkedTargetsForMode = linkageCache.getLinkage(targets, streetLayer, streetMode);
            // Transpose the cost table for propagation. Some tables are never used for propagation (like the
            // region-wide baseline). Transposing them only when needed should save a lot of memory.
            EgressCostTable egressCostTable = linkedTargetsForMode.getEgressCostTable();
            egressCostTable.destructivelyTransposeForPropagationAsNeeded();
            egressCostTables.add(egressCostTable);
        }
        return egressCostTables;
    }

    /**
     * Repeatedly performs propagation to each target, for each different mode of egress. Repeated propagation to the
     * same target point works, because each propagation call checks whether it reduces the travel time at each separate
     * iteration. This does mean that different iterations can use different modes, which matches situations where the
     * rider decides dynamically whether to call a cab or walk depending on the egress station's proximity to the
     * destination point.
     * It is possible that computation would be faster with the iteration order inverted to (mode, targetIndex) instead
     * of (targetIndex, mode).
     * All egress cost tables are known to be for the same StreetLayer and PointSet, just different modes.
     */
    public static void propagateToTarget(
            int targetIdx,
            int[] travelTimes,
            PathResultsRecorder pathsRecorder,
            List<EgressCostTable> egressCostTables,
            Map<StreetMode, Integer> modeSpeeds,
            Map<StreetMode, Integer> modeLimits,
            int[][] travelTimesToStop,
            int maxTripDurationSeconds
    ) {
        // Improve upon these non-transit travel times based on transit travel times to nearby stops.
        // This fills in perIterationTravelTimes and perIterationPaths for one particular target.
        for (EgressCostTable egressCostTable : egressCostTables) {
            // Grab the set of nearby stops for this target, with their distances.
            TIntIntMap pointToStopLinkageCostTable = egressCostTable.getCostTableForPoint(targetIdx);

            // Only try to propagate transit travel times if there are transit stops near this target.
            // Even if we don't propagate transit travel times, we still need to pass these non-transit times to
            // the reducer later in the caller, because you can walk even where there is no transit.
            if (pointToStopLinkageCostTable == null) continue;

            int egressModeSpeed = modeSpeeds.get(egressCostTable.linkedPointSet.streetMode);
            int egressTimeLimit = modeLimits.get(egressCostTable.linkedPointSet.streetMode);

            // Propagate all iterations from each relevant alighting stop out to this target.
            pointToStopLinkageCostTable.forEachEntry((stopIndex, linkageCost) -> {
                StreetTimeAndMode egressTimeAndMode = getEgressTimeAndMode(
                        stopIndex,
                        linkageCost,
                        egressModeSpeed,
                        egressTimeLimit,
                        egressCostTable
                );
                if (egressTimeAndMode != null) {
                    propagateTransit(
                            stopIndex,
                            travelTimes,
                            travelTimesToStop[stopIndex],
                            egressTimeAndMode,
                            maxTripDurationSeconds,
                            pathsRecorder
                    );
                }
                return true;
            });
        }

        // Ensure travel times are valid.
        checkTravelTimes(travelTimes);
    }

    /**
     * For every "iteration" (departure minute and Monte Carlo schedule), find a complete travel time to the specified
     * target from the given nearby stop, and update the best known time for that iteration and target.
     * Also record the best paths if we're going to be saving transit path details.
     */
    private static void propagateTransit(
            int stopIndex,
            int[] travelTimesToTarget,
            int[] travelTimesToStop,
            StreetTimeAndMode egressTimeAndMode,
            int maxTravelTimeSeconds,
            PathResultsRecorder pathsRecorder
    ) {
        if (pathsRecorder.isRecordingTarget()) {
            LOG.info("Max: {} Egress: {}", maxTravelTimeSeconds, egressTimeAndMode.time);
        }
        for (int iteration = 0; iteration < travelTimesToStop.length; iteration++) {
            // The travel time (in seconds) needed to reach this stop. Note this is indeed a duration, as calculated in
            // the Raptor route() method.
            int timeToStop = travelTimesToStop[iteration];
            // Skip propagation if the travel time to reach this stop is longer than the maximum travel time
            if (timeToStop >= maxTravelTimeSeconds) continue;

            int recordedTimeToTarget = travelTimesToTarget[iteration];
            // Skip if the travel time is faster via a different stop.
            if (timeToStop >= recordedTimeToTarget) continue;

            int newTimeToTarget = timeToStop + egressTimeAndMode.time;
            if (newTimeToTarget >= maxTravelTimeSeconds) continue;
            // Skip if the new time via this stop is longer than a previously recorded time
            if (newTimeToTarget >= recordedTimeToTarget) continue;

            // To reach this target in this iteration, alighting at this stop and proceeding by this egress mode is
            // faster than any previously checked stop/egress mode combination. Because that's the case, update the
            // best known travel time and the corresponding path.
            travelTimesToTarget[iteration] = newTimeToTarget;
            pathsRecorder.setTargetIterationValues(iteration, stopIndex, egressTimeAndMode);
        }
    }

    /**
     * For a given stop, find the mode and egress time to the linked target.
     */
    static StreetTimeAndMode getEgressTimeAndMode(
            int stopIndex,
            int linkageCost,
            int speedMillimetersPerSecond,
            int egressLegTimeLimitSeconds,
            EgressCostTable egressCostTable
    ) {
        int secondsFromStopToTarget;
        if (egressCostTable.linkageCostUnit == StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS) {
            secondsFromStopToTarget = linkageCost / speedMillimetersPerSecond;
        } else if (egressCostTable.linkageCostUnit == StreetRouter.State.RoutingVariable.DURATION_SECONDS) {
            secondsFromStopToTarget = linkageCost;
        } else {
            throw new UnsupportedOperationException("Linkage costs have an unknown unit.");
        }
        if (secondsFromStopToTarget >= egressLegTimeLimitSeconds) return null;

        // Account for any additional delay waiting for pickup at the egress stop.
        if (egressCostTable.egressStopDelaysSeconds != null) {
            int delayAtEgress = egressCostTable.egressStopDelaysSeconds[stopIndex];
            if (delayAtEgress < 0) {
                // Pickup for this mode not allowed at this stop, so iteration should continue
                return null;
            } else {
                secondsFromStopToTarget += delayAtEgress;
            }
        }

        return new StreetTimeAndMode(
                secondsFromStopToTarget,
                egressCostTable.linkedPointSet.streetMode
        );
    }

    public static void checkTravelTimes(int[] travelTimes) throws IllegalArgumentException {
        for (int i : travelTimes) {
            checkArgument(i >= 0, "Travel times must be positive.");
        }
    }
}