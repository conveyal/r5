package com.conveyal.r5.profile;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.TravelTimeReducer;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.PathWriter;
import com.conveyal.r5.streets.LinkedPointSet;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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

    /** When writing out paths to targets (for static sites), how many paths to save (centered on the median). */
    public static final int N_PATHS_PER_TARGET = 3;

    /**
     * The maximum travel time we will record and report. To limit calculation time and avoid overflow places this
     * many seconds from the origin are just considered unreachable.
     */
    public int cutoffSeconds = 120 * 60;

    /** The targets, linked to the street network. */
    public LinkedPointSet targets;

    /** the profilerequest (used for walk speed etc.) */
    public ProfileRequest request;

    /** how travel times are summarized and written or streamed back to a client TODO inline that whole class here. */
    public TravelTimeReducer travelTimeReducer;

    /** If non-null, methods will be called on this object to select and write out paths for a static site.*/
    public PathWriter pathWriter;

    /** Times at targets using the street network */
    public int[] nonTransitTravelTimesToTargets;

    /** Times at transit stops for each iteration. Plus a transposed version of that same matrix as an optimization. */
    public int[][] travelTimesToStopsForIteration, travelTimesToStop;

    /** The number of "iterations" (departure minutes & Monte Carlo schedules) and the number of stops. */
    private int nIterations, nStops;

    /** If non-null, the propagator should use these states to propagate out all the details of the paths, not just total travel time. */
    public List<RaptorState> statesEachIteration = null;

    /** Whether to break travel times down into walk, wait, and ride time. */
    private boolean calculateComponents;

    /**
     * Cache pre-multiplied integer value to avoid float math in loop below.
     * At one point we believed float math was slowing down this loop, however it's debatable whether that was due to
     * casts, the operations themselves, or the fact that the operations were being completed with doubles rather than
     * floats.
     */
    int speedMillimetersPerSecond;

    /**
     * STATE VARIABLES RESET WHEN PROCESSING EACH TARGET.
     * These track the characteristics of the best paths known to the target currently being processed.
     * Retains the best known total travel time, the breakdown of that travel time, and the path used to achieve that
     * best known travel time, for each iteration of the RAPTOR algorithm.
     */
    private int[] perIterationTravelTimes;
    private PathDetails[] perIterationDetails; // If travel time breakdown and paths are needed, groups the stop number and travel time so they can be sorted together.

    /**
     * Constructor.
     */
    public PerTargetPropagater(LinkedPointSet targets, AnalysisTask task,
                               int[][] travelTimesToStopsForIteration,
                               int[] nonTransitTravelTimesToTargets) {
        this.targets = targets;
        this.request = task;
        this.travelTimesToStopsForIteration = travelTimesToStopsForIteration;
        this.nonTransitTravelTimesToTargets = nonTransitTravelTimesToTargets;
        // If we're making a static site we'll break travel times down into components and make paths.
        // This expects the statesEachIteration and pathWriter fields to be set separately by the caller.
        this.calculateComponents = task.makeStaticSite;
        speedMillimetersPerSecond = (int) (request.walkSpeed * 1000);
        nIterations = travelTimesToStopsForIteration.length;
        nStops = travelTimesToStopsForIteration[0].length;
        invertTravelTimes();
    }


    /**
     * Extra work if breakdown of travel time and paths requested (for a static site).
     */
    private void calculateComponentsAndPaths() {

        // We only want to build the few full paths we will actually use, based on the stop and iteration number
        // that was used to achieve the total travel time.
        // This is hackish, but outside the travel time reducer we are going to re-sort the travel times.
        // AND I'm always returning the median even though the TravelTimeReducer knows the indexes of the selected percentiles!
        // FIXME this comparator might be slow because it's extracting the keys as objects not primitives
        // FIXME weirdly the perIterationDetails often only has a few non-null entries and therefore no median
        // Even weirder they all seem to have the same stop and travel time. How can the travel time be identical at different departure minutes?
        Arrays.sort(perIterationDetails, Comparator.comparing(details -> {
            // Sort null (no path) above all other travel times as "unreachable".
            if (details == null) return Integer.MAX_VALUE;
            else return details.travelTime;
        }));
        // Try to find the requested number of paths around and below the median travel time.
        List<Path> paths = new ArrayList<>();
        int iterationIndex = (perIterationDetails.length / 2) + (N_PATHS_PER_TARGET / 2);
        while (iterationIndex > 0) {
            PathDetails pathDetails = perIterationDetails[iterationIndex];
            if (pathDetails == null) {
                continue;
            }
            int totalTravelTime = 0;
            int inVehicleTime = 0;
            int waitTime = 0;
            // Set the travel time components to something non-zero if this target was reached by transit.
            // Grab the full state vector for the iteration that was used to achieve the total travel time.
            if (pathDetails.iteration != iterationIndex) {
                throw new AssertionError("Iteration index mismatch.");
            }
            RaptorState state = statesEachIteration.get(iterationIndex);
            int stop = pathDetails.stop;
            // Build up other components of travel time from that state.
            inVehicleTime = state.nonTransferInVehicleTravelTime[stop] / 60;
            waitTime = state.nonTransferWaitTime[stop] / 60;
            totalTravelTime = pathDetails.travelTime / 60;
            if (inVehicleTime + waitTime > totalTravelTime) {
                LOG.info("Wait and in vehicle travel time greater than total time");
            }
            // Only compute a path if this stop was reached.
            // FIXME aren't we already certain it was reached based on non-null pathDetails?
            if (state.bestNonTransferTimes[stop] != FastRaptorWorker.UNREACHED) {
                paths.add(new Path(state, stop));
            }
        }
        // If we didn't find the required number of paths, pad the list with nulls.
        while (paths.size() < N_PATHS_PER_TARGET) {
            paths.add(null);
        }
        // TODO Somehow report these in-vehicle, wait and walk breakdown values alongside the total travel time.
        // TODO WalkTime should be calculated per-iteration, as it may not hold for some summary statistics that stat(total) = stat(in-vehicle) + stat(wait) + stat(walk).
        if (pathWriter != null) {
            // Call with null if this target is unreached, because this method must be called for every target in order.
            pathWriter.recordPathsForTarget(paths);
        }

    }

    /**
     * After constructing a propagator and setting any additional options or optional fields,
     * call this method to actually perform the travel time propagation.
     */
    public OneOriginResult propagate () {
        targets.makePointToStopDistanceTablesIfNeeded();
        long startTimeMillis = System.currentTimeMillis();

        // perIterationTravelTimes and perIterationDetails are reused when processing each target.
        perIterationTravelTimes = new int[nIterations];
        if (calculateComponents){
            // Retain additional information to report travel time breakdown and paths to targets.
            perIterationDetails = new PathDetails[nIterations];
        }

        for (int targetIdx = 0; targetIdx < targets.size(); targetIdx++) {

            // Initialize the travel times to that achieved without transit (if any).
            // These travel times do not vary with departure time or MC draw, so they are all the same at a given target.
            Arrays.fill(perIterationTravelTimes, nonTransitTravelTimesToTargets[targetIdx]);

            // Clear out the details array if we're building one. These are transit solution details, so they remain
            // null until we find a good transit solution.
            if (perIterationDetails != null) Arrays.fill(perIterationDetails, null);

            // Improve upon these non-transit travel times based on transit travel times to nearby stops.
            propagateTransit(targetIdx);

            travelTimeReducer.recordTravelTimesForTarget(targetIdx, perIterationTravelTimes);

            if (calculateComponents) {
                calculateComponentsAndPaths();
            }
        }
        LOG.info("Propagating {} iterations from {} stops to {} targets took {}s",
                nIterations, nStops, targets.size(), (System.currentTimeMillis() - startTimeMillis) / 1000d
        );
        if (pathWriter != null) {
            pathWriter.finishAndStorePaths();
        }
        targets = null; // Prevent later reuse of this propagator instance.
        return travelTimeReducer.finish();
    }

    /**
     * Transpose the travel times to stops array in order to provide better memory locality in the tight loop below.
     * We have confirmed that this provides a significant speedup. TODO quantify that speedup and record here.
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
        long startTime = System.currentTimeMillis();
        travelTimesToStop = new int[nStops][nIterations];
        for (int iteration = 0; iteration < nIterations; iteration++) {
            for (int stop = 0; stop < nStops; stop++) {
                travelTimesToStop[stop][iteration] = travelTimesToStopsForIteration[iteration][stop];
            }
        }
        LOG.info("Travel time matrix transposition took {} msec", System.currentTimeMillis() - startTime);
    }

    /**
     * For every "iteration" (departure minute and Monte Carlo schedule), find a complete travel time to the current
     * target from the given nearby stop, and update the best known time for that iteration and target.
     * Also record the best paths if we're going to be saving transit path details.
     */
    private void propagateTransit (int targetIndex) {
        // Grab the set of nearby stops for this target, with their distances.
        TIntIntMap pointToStopDistanceTable = targets.pointToStopDistanceTables.get(targetIndex);
        // Only try to propagate transit travel times if there are transit stops near this target.
        // Even if we don't propagate transit travel times, we still need to pass these non-transit times to
        // the reducer below, because you can walk even where there is no transit.
        if (pointToStopDistanceTable != null) {
            pointToStopDistanceTable.forEachEntry((stop, distanceMillimeters) -> {
                for (int iteration = 0; iteration < nIterations; iteration++) {
                    int timeAtStop = travelTimesToStop[stop][iteration];
                    if (timeAtStop > cutoffSeconds || timeAtStop > perIterationTravelTimes[iteration]) {
                        // Skip propagation if all resulting times will be greater than the cutoff and
                        // cannot improve on the best known time at this iteration. Also avoids overflow.
                        continue;
                    }
                    // Propagate from the current stop out to the target.
                    int timeAtTarget = timeAtStop + distanceMillimeters / speedMillimetersPerSecond;
                    if (timeAtTarget < cutoffSeconds &&
                        timeAtTarget < perIterationTravelTimes[iteration]) {
                        // To reach this target, alighting at this stop is faster than any previously checked stop.
                        perIterationTravelTimes[iteration] = timeAtTarget;
                        if (calculateComponents) {
                            PathDetails pathDetails = new PathDetails();
                            pathDetails.stop = stop;
                            pathDetails.iteration = iteration;
                            pathDetails.travelTime = timeAtTarget;
                            perIterationDetails[iteration] = pathDetails;
                        }
                    }
                }
                return true; // Trove "continue iteration" signal.
            });
        }

    }

    /**
     * This associates a bunch of information including the iteration number with the total travel time, so that when
     * we sort on travel time and select some specific percentiles, we know which iteration of which stop that travel
     * time came from.
     */
    private static class PathDetails {
        public int stop;
        public int iteration;
        public int travelTime;
    }

}
