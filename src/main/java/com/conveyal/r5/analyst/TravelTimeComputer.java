package com.conveyal.r5.analyst;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.PathResultsRecorder;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.analyst.scenario.PickupWaitTimes;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.DominatingList;
import com.conveyal.r5.profile.FareDominatingList;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.profile.PropagationTimer;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.TransitPathsPerIteration;
import com.conveyal.r5.streets.EgressCostTable;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static com.conveyal.r5.analyst.scenario.PickupWaitTimes.NO_SERVICE_HERE;
import static com.conveyal.r5.analyst.scenario.PickupWaitTimes.NO_WAIT_ALL_STOPS;

/**
 * This computes a surface representing travel time from one origin to all destination cells, and writes out a
 * flattened 3D array, with each pixel of a 2D grid containing the different percentiles of travel time requested
 * by the frontend. This is called the "access grid" format and is distinct from the "destination grid" format in
 * that holds multiple values per pixel and has no inter-cell delta coding. It also has JSON concatenated on the
 * end with any scenario application warnings.
 *
 * TODO: we should merge these grid formats and update the spec to allow JSON errors at the end.
 * TODO: try to decouple the internal representation of the results from how they're serialized to the API.
 */
public class TravelTimeComputer {
    public static final int MM_PER_METER = 1000;
    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeComputer.class);

    /**
     * The TravelTimeComputer can make travel time grids, accessibility indicators, or (eventually) both depending
     * on what's in the task it's given. TODO factor out each major step of this process into private methods.
     */
    public static OneOriginResult computeTravelTimes(AnalysisWorkerTask request, TransportNetwork network) {

        // 0. Preliminary range checking and setup =====================================================================
        if (!request.directModes.equals(request.accessModes)) {
            throw new IllegalArgumentException("Direct mode may not be different than access mode in Analysis.");
        }

        // If this request includes a fare calculator, inject the transport network's transit layer into it.
        // This is threadsafe because deserializing each incoming request creates a new fare calculator instance.
        if (request.inRoutingFareCalculator != null) {
            request.inRoutingFareCalculator.transitLayer = network.transitLayer;
        }

        // Task types
        boolean isRegionalTask = request instanceof RegionalTask;
        boolean isTravelTimeSurfaceTask = request instanceof TravelTimeSurfaceTask;

        // Is this a `oneToOne` task?
        RegionalTask regionalTask = isRegionalTask ? ((RegionalTask) request) : null;
        boolean oneToOne = isRegionalTask && regionalTask.oneToOne;

        // Are we storing paths?
        boolean storePaths = request.makeTauiSite || request.includePathResults;

        // Set the destination index for storing single paths
        int destinationIndexForPaths = -1;

        // We will not record or report travel times for paths this long or longer. To limit calculation time and avoid
        // overflow, places at least this many seconds from the origin are simply considered unreachable.
        int maxTripDurationSeconds = request.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE;

        // Find the set of destinations for a travel time calculation, not yet linked to the street network, and with
        // no associated opportunities. By finding the extents and destinations up front, we ensure the exact same
        // destination pointset is used for all steps below.
        // This reuses the logic for finding the appropriate grid size and linking, which is now in the NetworkPreloader.
        // We could change the preloader to retain these values in a compound return type, to avoid repetition here.
        PointSet destinations;
        if (isRegionalTask
                && !request.makeTauiSite
                && request.destinationPointSets[0] instanceof FreeFormPointSet
        ) {
            // Freeform; destination pointset was set by handleOneRequest in the main AnalystWorker
            destinations = request.destinationPointSets[0];
            if (regionalTask.oneToOne) destinationIndexForPaths = request.taskId;

        } else {
            // Gridded (non-freeform) destinations. The extents are found differently in regional and single requests.
            WebMercatorExtents destinationGridExtents = request.getWebMercatorExtents();
            // Make a WebMercatorGridPointSet with the right extents, referring to the network's base grid and linkage.
            WebMercatorGridPointSet gridPointSet = AnalysisWorkerTask.gridPointSetCache.get(
                    destinationGridExtents,
                    network.fullExtentGridPointSet
            );
            if (request.includePathResults) {
                if (isTravelTimeSurfaceTask || regionalTask.oneToOne) {
                    destinationIndexForPaths = gridPointSet.getPointIndexContaining(request.toLon, request.toLat);
                }
            }
            destinations = gridPointSet;
        }

        // Store `nIterations` expected. Used to size arrays.
        int nIterations = request.getTotalIterations(network.transitLayer.hasFrequencies);

        // Create an object that accumulates travel times at each destination, simplifying them into percentiles.
        // Set timesPerDestination depending on how waiting time/travel time variability will be sampled
        TravelTimeReducer travelTimeReducer = new TravelTimeReducer(
                request,
                nIterations
        );

        // Ensure opportunity extents match the task.
        if (destinations instanceof WebMercatorGridPointSet) {
            travelTimeReducer.checkOpportunityExtents(destinations);
        }

        // I. Access to transit (or direct non-transit travel to destination) ==========================================
        // Use one or more modes to access transit stops, retaining the reached transit stops as well as the travel
        // times to the destination points using those access modes.

        // A map from transit stop vertex indices to the travel time (in seconds) and mode used to reach those
        // vertices.
        StreetTimesAndModes bestAccessOptions = new StreetTimesAndModes();

        // Travel times in seconds to each destination point (or MAX_INT for unreachable points?)
        // Starts out as null but will be updated when any access leg search succeeds.
        PointSetTimes nonTransitTravelTimesToDestinations = null;

        // We will try to find a starting point in the street network and perform an access search with each street mode.
        // This tracks whether any of those searches (for any mode) were successfully connected to the street network.
        boolean foundAnyOriginPoint = false;

        // Convert from profile routing qualified modes to internal modes. This also ensures we don't route on
        // multiple LegModes that have the same StreetMode (such as BIKE and BIKE_RENT).
        EnumSet<StreetMode> accessModes = LegMode.toStreetModeSet(request.accessModes);

        // Prepare a set of modes, all of which will simultaneously be used for on-street egress.
        EnumSet<StreetMode> egressStreetModes = LegMode.toStreetModeSet(request.egressModes);

        // Pre-compute and retain a pre-multiplied integer speed to avoid float math in the loops below. These
        // two values used to be computed once when the propagator was constructed, but now they must be
        // computed separately for each egress StreetMode. At one point we believed float math was slowing down
        // this code, however it's debatable whether that was due to casts, the mathematical operations
        // themselves, or the fact that the operations were being completed with doubles rather than floats.
        Map<StreetMode, Integer> modeSpeeds = new HashMap<>();
        Map<StreetMode, Integer> modeTimeLimits = new HashMap<>();
        for (StreetMode mode : EnumSet.allOf(StreetMode.class)) {
            modeSpeeds.put(mode, (int) (request.getSpeedForMode(mode) * MM_PER_METER));
            modeTimeLimits.put(mode, request.getMaxTimeSeconds(mode));
        }

        // Perform a street search for each access mode. For now, direct modes must be the same as access modes.
        for (StreetMode accessMode : accessModes) {
            LOG.info("Performing street search for mode: {}", accessMode);

            // Look up pick-up service for an access leg.
            PickupWaitTimes.AccessService accessService =
                    network.streetLayer.getAccessService(request.fromLat, request.fromLon, accessMode);

            // When an on-demand mobility service is defined, it may not be available at this particular location.
            if (accessService == NO_SERVICE_HERE) {
                LOG.info("On-demand {} service is not available at this location, " +
                        "continuing to next access mode (if any).", accessMode);
                continue;
            }

            // Attempt to set the origin point before progressing any further.
            // This allows us to skip routing calculations if the network is entirely inaccessible. In the CAR_PARK
            // case this StreetRouter will be replaced but this still serves to bypass unnecessary computation.
            // The request must be provided to the StreetRouter before setting the origin point.
            StreetRouter sr = new StreetRouter(network.streetLayer);
            sr.profileRequest = request;
            sr.streetMode = accessMode;
            if ( ! sr.setOrigin(request.fromLat, request.fromLon)) {
                // Short circuit around routing and propagation if the origin point was not attached to the street network.
                LOG.info("Origin point could not be linked to the street network for mode {}.", accessMode);
                continue;
            }
            foundAnyOriginPoint = true;

            // The code blocks below identify transit stations reachable from the origin and produce a grid of
            // non-transit travel times that will later be merged with the transit travel times.
            // Note: this is essentially the same thing that is happening when creating linkage cost tables for the
            // egress end of the trip. We could probably reuse a method for both (getTravelTimesFromPoint).
            // Note: Access searches (which minimize travel time) are asymmetric with the egress cost tables (which
            // often minimize distance to allow reuse at different speeds).

            // Preserve past behavior: only apply bike or walk time limits when those modes are used to access transit.
            // The overall time limit specified in the request may further decrease that mode-specific limit.
            {
                int limitSeconds = maxTripDurationSeconds;
                if (request.hasTransit()) {
                    limitSeconds = Math.min(limitSeconds, request.getMaxTimeSeconds(accessMode));
                }
                sr.timeLimitSeconds = limitSeconds;
            }

            // Even if generalized cost tags were present on the input data, we always minimize travel time.
            // The generalized cost calculations currently increment time and weight by the same amount.
            sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            sr.route();
            // Change to walking in order to reach transit stops in pedestrian-only areas like train stations.
            // This implies you are dropped off or have a very easy parking spot for your vehicle.
            // This kind of multi-stage search should also be used when building egress distance cost tables.
            if (accessMode != StreetMode.WALK) {
                sr.keepRoutingOnFoot();
            }

            if (request.hasTransit()) {
                // Find access times to transit stops, keeping the minimum across all access street modes.
                // Note that getReachedStops() returns the routing variable units, not necessarily seconds.
                // TODO add logic here if linkedStops are specified in pickupDelay?
                TIntIntMap travelTimesToStopsSeconds = sr.getReachedStops();
                if (accessService != NO_WAIT_ALL_STOPS) {
                    LOG.info("Delaying transit access times by {} seconds (to wait for {} pick-up).",
                            accessService.waitTimeSeconds, accessMode);
                    if (accessService.stopsReachable != null) {
                        travelTimesToStopsSeconds.retainEntries((k, v) -> accessService.stopsReachable.contains(k));
                    }
                    travelTimesToStopsSeconds.transformValues(i -> i + accessService.waitTimeSeconds);
                }
               bestAccessOptions.update(travelTimesToStopsSeconds, accessMode);
            }

            // Calculate times to reach destinations directly by this street mode, without using transit.
            //
            // The current implementation iterates over every cell in the destination grid. That usually makes sense
            // for non-transit searches, where we need to evaluate times up to the full travel time limit. But for
            // transit searches, where sr.timeLimitSeconds is typically small, it may not make sense to iterate over
            // every cell in a (possibly huge) destination grid. If this is measured to be inefficient, we could
            // construct a sub-grid that's an envelope around sr.originSplit's lat/lon (sized according to sr
            // .timeLimitSeconds and a mode-specific speed?), then iterate over the points in that sub-grid.
            {
                LinkedPointSet linkedDestinations = network.linkageCache.getLinkage(
                        destinations,
                        network.streetLayer,
                        accessMode
                );

                int streetSpeedMillimetersPerSecond = modeSpeeds.get(accessMode);
                if (streetSpeedMillimetersPerSecond <= 0) {
                    throw new IllegalArgumentException("Speed of access mode must be greater than 0.");
                }

                Split origin = sr.getOriginSplit();

                PointSetTimes pointSetTimes = linkedDestinations.eval(
                        sr::getTravelTimeToVertex,
                        streetSpeedMillimetersPerSecond,
                        modeSpeeds.get(StreetMode.WALK),
                        origin
                );

                if (accessService != NO_WAIT_ALL_STOPS) {
                    LOG.info("Delaying direct travel times by {} seconds (to wait for {} pick-up).",
                            accessService.waitTimeSeconds, accessMode);
                    if (accessService.stopsReachable != null) {
                        // Disallow direct travel to destination if pickupDelay zones are associated with stops.
                        pointSetTimes = PointSetTimes.allUnreached(destinations);
                    } else {
                        // Allow direct travel to destination using services not associated with specific stops.
                        pointSetTimes.incrementAllReachable(accessService.waitTimeSeconds);
                    }
                }
                nonTransitTravelTimesToDestinations = PointSetTimes.minMerge(nonTransitTravelTimesToDestinations, pointSetTimes);
            }
        }

        // Handle park+ride, a mode represented in the request LegMode but not in the internal StreetMode.
        // FIXME this special case for CAR_PARK currently overwrites any results from other access modes.
        //       That means computation is completely wasted and maybe duplicated.
        // This is pretty ugly, and should be integrated into the mode loop above.
        if (request.accessModes.contains(LegMode.CAR_PARK)) {
            // Currently first search from origin to P+R is hardcoded as time dominance variable for Max car time seconds
            // Second search from P+R to stops is not actually a search we just return list of all reached stops for each found P+R.
            // If multiple P+Rs reach the same stop, only one with shortest time is returned. Stops were searched for during graph building phase.
            // time to stop is time from CAR streetrouter to stop + CAR PARK time + time to walk to stop based on request walk speed
            // by default 20 CAR PARKS are found it can be changed with sr.maxVertices variable
            // FIXME we should not limit the number of car parks found.
            StreetRouter sr = new StreetRouter(network.streetLayer);
            sr.profileRequest = request;
            sr = PointToPointQuery.findParkRidePath(request, sr, network.transitLayer);
            if (sr == null) {
                // Origin not found. Signal this using the same flag as the other modes do.
                foundAnyOriginPoint = false;
            } else {
                bestAccessOptions.update(sr.getReachedStops(), StreetMode.CAR);
                foundAnyOriginPoint = true;
            }
            // Disallow non-transit access.
            // TODO should we allow non transit access with park and ride? Maybe with an additional parameter?
            nonTransitTravelTimesToDestinations = PointSetTimes.allUnreached(destinations);
        }

        if (!foundAnyOriginPoint) {
            // The origin point was not even linked to the street network.
            // Calling finish() before streaming in any travel times to destinations is designed to produce the right result.
            LOG.info("Origin point was outside the street network. Skipping routing and propagation, and returning default result.");
            return new OneOriginResult(
                    travelTimeReducer.getTravelTimeResult(),
                    travelTimeReducer.getAccessibilityResult(),
                    null
            );
        }

        if (nonTransitTravelTimesToDestinations.travelTimes.length != destinations.featureCount()) {
            throw new IllegalArgumentException("Non-transit travel times must have the same number of entries as there are points.");
        }

        // Short circuit unnecessary transit routing: If the origin was linked to a road, but no transit stations
        // were reached, return the non-transit grid as the final result.
        if (request.transitModes.isEmpty() || bestAccessOptions.streetTimesAndModes.isEmpty()) {
            LOG.info("Skipping transit search. No transit stops were reached or no transit modes were selected.");
            int nTargets = nonTransitTravelTimesToDestinations.size();
            if (oneToOne) nTargets = 1;
            for (int target = 0; target < nTargets; target++) {
                // TODO: pull this loop out into a method: travelTimeReducer.recordPointSetTimes(accessTimes)
                final int travelTimeSeconds = nonTransitTravelTimesToDestinations.getTravelTimeToPoint(target);
                travelTimeReducer.recordUnvaryingTravelTimeAtTarget(target, travelTimeSeconds);
            }
            return new OneOriginResult(
                    travelTimeReducer.getTravelTimeResult(),
                    travelTimeReducer.getAccessibilityResult(),
                    null
            );
        }

        // II. Transit Routing ========================================================================================

        // Record paths per iteration for use during propagation.
        TransitPathsPerIteration pathsPerIteration = new TransitPathsPerIteration(
                storePaths,
                bestAccessOptions
        );

        // Transit stops were reached. Perform transit routing from those stops to all other reachable stops. The result
        // is a travel time in seconds for each iteration (departure time x monte carlo draw), for each transit stop.
        int[][] transitTravelTimesToStops = request.inRoutingFareCalculator == null
                ? routeToStops(network.transitLayer, request, bestAccessOptions.getTimes(), pathsPerIteration)
                : routeToStopsWithFares(request, network);

        // III. Egress Propagation ======================================================================================
        // Propagate these travel times for every iteration at every stop out to the destination points, via streets.
        PropagationTimer timer = new PropagationTimer();
        timer.fullPropagation.start();

        // Invert and transpose data to speed up inner loops.
        timer.transposition.start();
        int[][] invertedTravelTimesToStops = PerTargetPropagater.getInvertedTravelTimesToStops(transitTravelTimesToStops);

        // This will link the destinations to the street layer for all modes as needed.
        List<EgressCostTable> costTables = PerTargetPropagater.getTransposedEgressCostTables(
                destinations,
                egressStreetModes,
                network.streetLayer,
                network.linkageCache
        );
        timer.transposition.stop();

        int nPathResults = isTravelTimeSurfaceTask || regionalTask.oneToOne
                ? 1
                : request.nTargetsPerOrigin();

        // Create the paths recorder
        PathResultsRecorder pathsRecorder = new PathResultsRecorder(
                network.transitLayer,
                pathsPerIteration,
                nPathResults,
                destinationIndexForPaths,
                nIterations
        );

        // In most tasks, we want to propagate travel times for each origin out to all the destinations.
        int startTarget = 0;
        int endTarget = nonTransitTravelTimesToDestinations.size();
        if (isRegionalTask && regionalTask.oneToOne) {
            startTarget = request.taskId;
            endTarget = startTarget + 1;
        }

        // For each target, record the total travel times with propagation to the destinations.
        for (int targetIdx = startTarget; targetIdx < endTarget; targetIdx++) {
            timer.reducer.start();
            // Generate base travel times array starting with non-transit times.
            int nonTransitTravelTimeToTarget = nonTransitTravelTimesToDestinations.getTravelTimeToPoint(targetIdx);
            int[] travelTimesToTarget = travelTimeReducer.initializeTravelTimes(nonTransitTravelTimeToTarget);
            timer.reducer.stop();

            // Initiate path recording for this target
            pathsRecorder.startRecordingTarget(targetIdx);

            timer.propagation.start();
            PerTargetPropagater.propagateToTarget(
                    targetIdx,
                    travelTimesToTarget,
                    pathsRecorder,
                    costTables,
                    modeSpeeds,
                    modeTimeLimits,
                    invertedTravelTimesToStops,
                    maxTripDurationSeconds
            );
            timer.propagation.stop();

            // Record paths accumulated from propagation.
            pathsRecorder.recordPathsForTarget(
                    targetIdx,
                    travelTimesToTarget
            );

            // Extract the requested percentiles and save them (and/or the resulting accessibility indicator values)
            timer.reducer.start();
            travelTimeReducer.recordHistogramForTarget(targetIdx, travelTimesToTarget);

            // NB: this next method sorts the travel times and should be the last one to use the array.
            travelTimeReducer.extractTravelTimePercentilesAndRecord(targetIdx, travelTimesToTarget);
            timer.reducer.stop();
        }
        timer.fullPropagation.stop();
        timer.log();

        // Combine the travel times, accessibility results, and paths into a `OneOriginResult`.
        return new OneOriginResult(
                travelTimeReducer.getTravelTimeResult(),
                travelTimeReducer.getAccessibilityResult(),
                pathsRecorder
        );
    }

    static int[][] routeToStops(
            TransitLayer transitLayer,
            AnalysisWorkerTask task,
            TIntIntMap bestAccessOptionsTimes,
            TransitPathsPerIteration transitPathsPerIteration
    ) {
        FastRaptorWorker worker = new FastRaptorWorker(
                transitLayer,
                task,
                bestAccessOptionsTimes,
                transitPathsPerIteration
        );
        // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
        // Returns the total travel times as a 2D array of [searchIteration][destinationStopIndex].
        // Additional detailed path information is retained in the FastRaptorWorker after routing.
        return worker.route();
    }

    static int[][] routeToStopsWithFares(AnalysisWorkerTask task, TransportNetwork network) {
        // TODO maxClockTime could provide a tighter bound, as it could be based on the actual departure time, not the last possible
        IntFunction<DominatingList> listSupplier =
                (departureTime) -> new FareDominatingList(
                        task.inRoutingFareCalculator,
                        task.maxFare,
                        departureTime + task.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE);
        McRaptorSuboptimalPathProfileRouter mcRaptorWorker = new McRaptorSuboptimalPathProfileRouter(network,
                task, null, null, listSupplier, InRoutingFareCalculator.getCollator(task));
        mcRaptorWorker.route();
        return mcRaptorWorker.getBestTimes();
    }
}