package com.conveyal.r5.profile;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.GenericReducer;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.PathWriter;
import com.conveyal.r5.streets.LinkedPointSet;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

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

    // FIXME why are these fields public?

    /** The travel time cutoff in this regional analysis FIXME why would this have a default value? */
    public int cutoffSeconds = 120 * 60;

    /** The targets, linked to the street network. */
    public LinkedPointSet targets;

    /** the profilerequest (used for walk speed etc.) */
    public ProfileRequest request;

    /** how travel times are summarized and written or streamed back to a client TODO inline that whole class here. */
    public GenericReducer reducer;

    /** how paths are grouped and written */
    public PathWriter pathWriter;

    /** Times at targets using the street network */
    public int[] nonTransitTravelTimesToTargets;

    /** Times at transit stops for each iteration TODO make convenience field for number of iterations (length) */
    public int[][] travelTimesToStopsEachIteration, invertedTravelTimesToStops;

    /**
     * For the origin stop being handled by this propagator, the various components of the travel time as well as the
     * index of the path producing that travel time, to each stop, in each iteration (departure minute + MC draw) of
     * RAPTOR. Dimension order: array[stop][iteration]
     */
    private int[][] inVehicleTimesToStops, waitTimesToStops, pathsToStops;

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
    private int[] perIterationPaths, perIterationTravelTimes, perIterationWaitTimes, perIterationInVehicleTimes;

    public PerTargetPropagater(LinkedPointSet targets, AnalysisTask task, int[][] travelTimesToStopsEachIteration, int[] nonTransitTravelTimesToTargets, int[][] inVehicleTimesToStops, int[][] waitTimesToStops, int[][] paths) {
        this.targets = targets;
        this.request = task;
        this.travelTimesToStopsEachIteration = travelTimesToStopsEachIteration;
        this.nonTransitTravelTimesToTargets = nonTransitTravelTimesToTargets;
        this.inVehicleTimesToStops = inVehicleTimesToStops;
        this.waitTimesToStops = waitTimesToStops;
        this.pathsToStops = paths;
        this.calculateComponents = inVehicleTimesToStops != null && waitTimesToStops !=null && pathsToStops != null;
        speedMillimetersPerSecond = (int) (request.walkSpeed * 1000);
        invertTravelTimes();
    }

    /**
     * After constructing a propagator, call this method to actually perform the travel time propagation.
     */
    public OneOriginResult propagate () {
        targets.makePointToStopDistanceTablesIfNeeded();
        long startTimeMillis = System.currentTimeMillis();

        perIterationTravelTimes = new int[travelTimesToStopsEachIteration.length];
        if (calculateComponents){
            perIterationInVehicleTimes = new int[travelTimesToStopsEachIteration.length];
            perIterationWaitTimes = new int[travelTimesToStopsEachIteration.length];
            perIterationPaths = new int[travelTimesToStopsEachIteration.length];
        }

        for (int targetIdx = 0; targetIdx < targets.size(); targetIdx++) {
            // Initialize the travel times to that achieved without transit (if any), which does not vary with MC draw.
            Arrays.fill(perIterationTravelTimes, nonTransitTravelTimesToTargets[targetIdx]);

            // Improve upon the non-transit travel time based transit travel times to nearby stops.
            propagateTransit(targetIdx);

            // TODO add reducer for components of total travel time; walkTime should be calculated per-iteration, as it
            // may not hold for some summary statistics that stat(total) = stat(in-vehicle) + stat(wait) + stat(walk).
            reducer.recordTravelTimesForTarget(targetIdx, perIterationTravelTimes);

            if (pathWriter != null) {
                pathWriter.recordPathsForTarget(perIterationPaths);
            }
        }

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOG.info("Propagating {} iterations from {} stops to {} targets took {}s",
                travelTimesToStopsEachIteration.length,
                travelTimesToStopsEachIteration[0].length,
                targets.size(),
                totalTimeMillis / 1000d
        );
        if (pathWriter != null) {
            pathWriter.finishPaths();
        }
        targets = null; // Prevent later reuse of this propagator instance.
        return reducer.finish();
    }

    /**
     * Do not actually perform propagation, e.g. in locations where we know there is no transit service.
     * TODO call this in travelTimeComputer instead of directly calling reducer, which allows encapsulating reducer construction.
     */
    public OneOriginResult skipPropagation () {
        targets = null; // Prevent later reuse of this propagator instance.
        return reducer.finish();
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
     * stops. For eacmple, the Netherlands has about 70,000 stops, if you do 1,000 iterations to 70,000 stops, the
     * array being transposed and duplicated is 70,000 * 1000 * 4 bytes per int ~= 267 megabytes. It does not seem
     * worthwhile to change the routing algorithm to output already-transposed data, as that will create memory
     * locality problems elsewhere (since the pathfinding algorithm solves one iteration for all stops simultaneously).
     */
    private void invertTravelTimes() {
        long startTime = System.currentTimeMillis();
        invertedTravelTimesToStops = new int[travelTimesToStopsEachIteration[0].length][travelTimesToStopsEachIteration.length];
        for (int iteration = 0; iteration < travelTimesToStopsEachIteration.length; iteration++) {
            for (int stop = 0; stop < travelTimesToStopsEachIteration[0].length; stop++) {
                invertedTravelTimesToStops[stop][iteration] = travelTimesToStopsEachIteration[iteration][stop];
            }
        }
        LOG.info("Travel time matrix transposition took {} msec", System.currentTimeMillis() - startTime);
    }

    /**
     * For every "iteration" (departure minute and Monte Carlo schedule), find a complete travel time to the current
     * target from the given nearby stop, and update the best known time for that iteration and target.
     */
    private void propagateTransit (int targetIndex) {
        // Grab the set of nearby stops for this target, with their distances.
        TIntIntMap pointToStopDistanceTable = targets.pointToStopDistanceTables.get(targetIndex);
        // Only try to propagate transit travel times if there are transit stops near this target.
        // Even if we don't propagate transit travel times, we still need to pass these non-transit times to
        // the reducer below, because you can walk even where there is no transit.
        if (pointToStopDistanceTable != null) {
            pointToStopDistanceTable.forEachEntry((stop, distanceMillimeters) -> {
                for (int iteration = 0; iteration < perIterationTravelTimes.length; iteration++) {
                    int timeAtStop = invertedTravelTimesToStops[stop][iteration];
                    if (timeAtStop > cutoffSeconds || timeAtStop > perIterationTravelTimes[iteration])
                        continue; // avoid overflow
                    int timeAtTargetThisStop = timeAtStop + distanceMillimeters / speedMillimetersPerSecond;
                    if (timeAtTargetThisStop < cutoffSeconds) {
                        if (timeAtTargetThisStop < perIterationTravelTimes[iteration]) {
                            // using this stop to get to this target is faster than previously checked stops
                            perIterationTravelTimes[iteration] = timeAtTargetThisStop;
                            if (calculateComponents) {
                                perIterationInVehicleTimes[iteration] = inVehicleTimesToStops[iteration][stop];
                                perIterationWaitTimes[iteration] = waitTimesToStops[iteration][stop];
                                perIterationPaths[iteration] = pathsToStops[iteration][stop];
                            }
                        }
                    }
                }
                return true; // Trove "continue iteration" signal.
            });
        }
    }

}
