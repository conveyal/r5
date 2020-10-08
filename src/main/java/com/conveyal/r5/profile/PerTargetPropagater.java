package com.conveyal.r5.profile;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.PathScorer;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.TravelTimeReducer;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.PathWriter;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.streets.EgressCostTable;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

    public static final int SECONDS_PER_MINUTE = 60;
    public static final int MM_PER_METER = 1000;

    /**
     * We will not record or report travel times for paths this long or longer. To limit calculation time and avoid
     * overflow, places at least this many seconds from the origin are simply considered unreachable.
     */
    public final int maxTravelTimeSeconds;

    /** The targets, not yet linked to the street network. */
    private PointSet targets;

    /** All modes for which we want to perform egress propagation through the street network. */
    public final EnumSet<StreetMode> modes;

    /** One linkage for each street mode for which we want to extend travel times out from transit to destinations. */
    private final List<LinkedPointSet> linkedTargets;

    /** the profilerequest (used for walk speed etc.) */
    public final ProfileRequest request;

    /** how travel times are summarized and written or streamed back to a client TODO inline that whole class here. */
    public TravelTimeReducer travelTimeReducer;

    /** If non-null, methods will be called on this object to select and write out paths for a static site.*/
    public PathWriter pathWriter;

    /** Times at targets using the street network */
    private final int[] nonTransitTravelTimesToTargets;

    /** Times at transit stops for each iteration. Plus a transposed version of that same matrix as an optimization. */
    private int[][] travelTimesToStopsForIteration, travelTimesToStop;

    /**
     * The number of "iterations" (departure minutes & Monte Carlo schedules) and the number of stops and destination
     * points.
     */
    private final int nIterations, nStops, nTargets;

    /**
     * For each iteration of the raptor algorithm, an array of the path that yielded the best travel time to each
     * transit stop. May be null, only needs to be set if we're recording paths, as in a static site.
     */
    public List<Path[]> pathsToStopsForIteration = null;

    /** Whether to break travel times down into walk, wait, and ride time. */
    private boolean calculateComponents;

    /**
     * Whether to propagate only to a single target that corresponds to the origin (e.g. in a travel time savings
     * calculation).
     */
    private final boolean oneToOne;

    // STATE FIELDS WHICH ARE RESET WHEN PROCESSING EACH DESTINATION.
    // These track the characteristics of the best paths known to the target currently being processed.

    /**
     * Retains the best known total travel time to the current destination for each iteration of the RAPTOR algorithm.
     * TODO instead of a long-lived field this could be returned from the propagate function.
     */
    private int[] perIterationTravelTimes;

    /**
     * The transit path that yielded the best known travel time to the current destination for each iteration of the
     * raptor algorithm. Parallel to perIterationTravelTimes. Reset for each destination that is processed.
     */
    private Path[] perIterationPaths;

    private final PropagationTimer timer = new PropagationTimer();

    /**
     * Constructor.
     */
    public PerTargetPropagater(
            PointSet targets,
            StreetLayer streetLayer,
            EnumSet<StreetMode> modes,
            AnalysisWorkerTask task,
            int[][] travelTimesToStopsForIteration,
            int[] nonTransitTravelTimesToTargets
    ) {
        this.targets = targets;
        this.modes = modes;
        this.request = task;
        this.travelTimesToStopsForIteration = travelTimesToStopsForIteration;
        this.nonTransitTravelTimesToTargets = nonTransitTravelTimesToTargets;

        // If we're making a static site we'll break travel times down into components and make paths.
        // This expects the pathsToStopsForIteration and pathWriter fields to be set separately by the caller.
        calculateComponents = task.makeTauiSite;

        maxTravelTimeSeconds = task.maxTripDurationMinutes * SECONDS_PER_MINUTE;
        oneToOne = request instanceof RegionalTask && ((RegionalTask) request).oneToOne;
        nIterations = travelTimesToStopsForIteration.length;
        nStops = travelTimesToStopsForIteration[0].length;
        nTargets = targets.featureCount();
        linkedTargets = new ArrayList<>(modes.size());

        timer.fullPropagation.start();
        timer.transposition.start();
        invertTravelTimes();
        if (nonTransitTravelTimesToTargets.length != nTargets) {
            throw new IllegalArgumentException("Non-transit travel times must have the same number of entries as there are points.");
        }
        for (StreetMode streetMode : modes) {
            LinkedPointSet linkedTargetsForMode = streetLayer.parentNetwork.linkageCache
                    .getLinkage(targets, streetLayer, streetMode);
            // Transpose the cost table for propagation. Some tables are never used for propagation (like the
            // region-wide baseline). Transposing them only when needed should save a lot of memory.
            linkedTargetsForMode.getEgressCostTable().destructivelyTransposeForPropagationAsNeeded();
            linkedTargets.add(linkedTargetsForMode);
        }
        timer.transposition.stop();
        // Prevent top-level timer from counting any intervening actions until caller calls propagate()
        timer.fullPropagation.stop();
    }

    /**
     * After constructing a propagator and setting any additional options or optional fields,
     * call this method to actually perform the travel time propagation.
     */
    public OneOriginResult propagate () {

        timer.fullPropagation.start();

        // perIterationTravelTimes and perIterationDetails are reused when processing each target.
        perIterationTravelTimes = new int[nIterations];

        // Retain additional information about how the target was reached to report travel time breakdown and paths to targets.
        perIterationPaths = calculateComponents ? new Path[nIterations] : null;

        // In most tasks, we want to propagate travel times for each origin out to all the destinations.
        int startTarget = 0;
        int endTarget = nTargets;

        // However, in one-to-one tasks, each origin in a freeform pointset corresponds to a single destination at
        // the same position in the destinations pointset. So our target range is restricted to only one target.
        if (oneToOne) {
            startTarget = ((RegionalTask) request).taskId;
            endTarget = startTarget + 1;
        }

        for (int targetIdx = startTarget; targetIdx < endTarget; targetIdx++) {

            // Initialize the travel times to that achieved without transit (if any).
            // These travel times do not vary with departure time or MC draw, so they are all the same at a given target.
            Arrays.fill(perIterationTravelTimes, nonTransitTravelTimesToTargets[targetIdx]);

            // Clear out the Path array if we're building one. These are transit solution details, so they remain
            // null until we find a good transit solution.
            if (perIterationPaths != null) {
                Arrays.fill(perIterationPaths, null);
            }

            // Improve upon these non-transit travel times based on transit travel times to nearby stops.
            // This fills in perIterationTravelTimes and perIterationPaths for one particular target.
            timer.propagation.start();
            propagateTransit(targetIdx);
            timer.propagation.stop();

            // Construct the PathScorer before extracting percentiles because the scorer needs to make a copy of
            // the unsorted complete travel times.
            PathScorer pathScorer = null;
            if (calculateComponents) {
                // TODO optimization: skip this entirely if there is no transit access to the destination.
                // We know transit access is impossible in the caller when there are no reached stops.
                pathScorer = new PathScorer(perIterationPaths, perIterationTravelTimes);
            }

            // Extract the requested percentiles and save them (and/or the resulting accessibility indicator values)
            int targetToWrite = oneToOne ? 0 : targetIdx;
            timer.reducer.start();
            travelTimeReducer.extractTravelTimePercentilesAndRecord(targetToWrite, perIterationTravelTimes);
            timer.reducer.stop();

            if (calculateComponents) {
                // TODO Somehow report these in-vehicle, wait and walk breakdown values alongside the total travel time.
                // TODO WalkTime should be calculated per-iteration, as it may not hold for some summary statistics
                //      that stat(total) = stat(in-vehicle) + stat(wait) + stat(walk).
                // The perIterationTravelTimes are sorted as a side effect of the above travelTimeReducer call.
                // NOTE this is currently using only the first (lowest) travel time.
                Set<Path> selectedPaths = pathScorer.getTopPaths(pathWriter.nPathsPerTarget, perIterationTravelTimes[0]);
                pathWriter.recordPathsForTarget(selectedPaths);
            }
        }
        timer.fullPropagation.stop();
        timer.log();
        if (pathWriter != null) {
            pathWriter.finishAndStorePaths();
        }
        targets = null; // Prevent later reuse of this propagator instance.
        return travelTimeReducer.finish();
    }

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
    private void invertTravelTimes() {
        travelTimesToStop = new int[nStops][nIterations];
        for (int iteration = 0; iteration < nIterations; iteration++) {
            for (int stop = 0; stop < nStops; stop++) {
                travelTimesToStop[stop][iteration] = travelTimesToStopsForIteration[iteration][stop];
            }
        }
    }

    /**
     * Repeatedly performs propagation to the same target, for each different mode of egress.
     * Repeated propagation to the same target point works, because each propagation call checks whether it reduces
     * the travel time at each separate iteration. This does mean that different iterations can use different modes,
     * which matches situations where the rider decides dynamically whether to call a cab or walk depending on the
     * egress station's proximity to the destination point.
     *
     * It is possible that computation would be faster with the iteration order inverted to (mode, targetIndex) instead
     * of (targetIndex, mode).
     */
    private void propagateTransit (int targetIndex) {
        // All linked pointsets are known to be for the same StreetLayer and PointSet, just different modes.
        for (LinkedPointSet linkedPointSet : linkedTargets) {
            propagateTransit(targetIndex, linkedPointSet);
        }
    }

    /**
     * For every "iteration" (departure minute and Monte Carlo schedule), find a complete travel time to the specified
     * target from the given nearby stop, and update the best known time for that iteration and target.
     * Also record the best paths if we're going to be saving transit path details.
     * TODO verify if these are actually travel times (vs. clock times after midnight) and clarify code comments.
     * They appear to be travel times (are compared against cutoffSeconds which is a trip duration).
     */
    private void propagateTransit (int targetIndex, LinkedPointSet linkedTargets) {

        // Grab the set of nearby stops for this target, with their distances.
        EgressCostTable egressCostTable = linkedTargets.getEgressCostTable();
        TIntIntMap pointToStopLinkageCostTable = egressCostTable.getCostTableForPoint(targetIndex);
        StreetRouter.State.RoutingVariable unit = egressCostTable.linkageCostUnit;

        /**
         * Pre-compute and retain a pre-multiplied integer speed to avoid float math in the loop below.
         * These two variables used to be computed once when the propagator was constructed, but now they must be
         * computed separately for each egress StreetMode.
         * At one point we believed float math was slowing down this loop, however it's debatable whether that was due
         * to casts, the mathematical operations themselves, or the fact that the operations were being completed with
         * doubles rather than floats.
         */
        int speedMillimetersPerSecond = (int) (request.getSpeedForMode(linkedTargets.streetMode) * MM_PER_METER);
        int egressLegTimeLimitSeconds = request.getMaxTimeSeconds(linkedTargets.streetMode);

        // If handling car egress, and car hailing waiting times are defined, initialize with default hail wait time.
        // FIXME ensure this ^ is baked into the PickupDelay class

        // Only try to propagate transit travel times if there are transit stops near this target.
        // Even if we don't propagate transit travel times, we still need to pass these non-transit times to
        // the reducer later in the caller, because you can walk even where there is no transit.
        if (pointToStopLinkageCostTable != null) {
            // Propagate all iterations from each relevant alighting stop out to this target.
            pointToStopLinkageCostTable.forEachEntry((stop, linkageCost) -> {
                int secondsFromStopToTarget;
                if (unit == StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS) {
                    secondsFromStopToTarget = linkageCost / speedMillimetersPerSecond;
                } else if (unit == StreetRouter.State.RoutingVariable.DURATION_SECONDS) {
                    secondsFromStopToTarget = linkageCost;
                } else {
                    throw new UnsupportedOperationException("Linkage costs have an unknown unit.");
                }
                if (secondsFromStopToTarget < egressLegTimeLimitSeconds){
                    for (int iteration = 0; iteration < nIterations; iteration++) {
                        int timeAtStop = travelTimesToStop[stop][iteration];
                        if (timeAtStop >= maxTravelTimeSeconds || timeAtStop >= perIterationTravelTimes[iteration]) {
                            // Skip propagation if all resulting times will be greater than the cutoff and
                            // cannot improve on the best known time at this iteration. Also avoids overflow.
                            continue;
                        }

                        // TODO shouldn't all the below egress delays be baked into linkedTargets.getEgressCostTable()
                        //  .getCostTableForPoint(targetIndex)? At the end of the EgressCostTable constructor, we can
                        //  see via linkedPointSet.streetLayer.waitTimePolygons (or a new wrapper class
                        //  AccessEgressWaitTimes) whether each stop has an egress delay and add it in to all stops.
                        //  Applying the pickup delay modification creates a new street layer, so a new linkage.

                        // Account for any additional delay waiting for pickup at the egress stop.
                        // FIXME This adds delays to regular BICYCLE egress if BICYCLE_RENT egress has previously been
                        //  requested (triggering the building of egressStopDelayTables above, which leads to
                        //  non-null egressStopDelaysSeconds). Maybe this is fine -- as with CAR, the delays should
                        //  be ignored when running a scenario without pickup delay modifications.
                        if ((linkedTargets.streetMode == StreetMode.CAR || linkedTargets.streetMode == StreetMode.BICYCLE)
                                && linkedTargets.egressStopDelaysSeconds != null) {
                                    int delayAtEgress = linkedTargets.egressStopDelaysSeconds[stop];
                                    if (delayAtEgress < 0) {
                                        // Pickup for this mode not allowed at this stop, so trove iteration should
                                        // continue
                                        return true;
                                    } else {
                                        secondsFromStopToTarget += delayAtEgress;
                                    }
                        }

                        int timeAtTarget = timeAtStop + secondsFromStopToTarget;
                        if (timeAtTarget < maxTravelTimeSeconds && timeAtTarget < perIterationTravelTimes[iteration]) {
                            // To reach this target, alighting at this stop is faster than any previously checked stop.
                            perIterationTravelTimes[iteration] = timeAtTarget;
                            if (calculateComponents) {
                                Path[] pathsToStops = pathsToStopsForIteration.get(iteration);
                                perIterationPaths[iteration] = pathsToStops[stop];
                            }
                        }
                    }
                }
                return true; // Trove "continue iteration" signal.
            });
        }
    }


}
