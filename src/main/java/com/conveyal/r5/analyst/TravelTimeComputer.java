package com.conveyal.r5.analyst;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.PathWriter;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.profile.DominatingList;
import com.conveyal.r5.profile.FareDominatingList;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.function.IntFunction;

import static com.conveyal.r5.profile.PerTargetPropagater.MM_PER_METER;

/**
 * This computes a surface representing travel time from one origin to all destination cells, and writes out a
 * flattened 3D array, with each pixel of a 2D grid containing the different percentiles of travel time requested by
 * the frontend. This is called the "access grid"
 * format and is distinct from the "destination grid" format in that holds multiple values per pixel and has no
 * inter-cell delta coding. It also has JSON concatenated on the end with any scenario application warnings.
 * So TODO: we should merge these grid formats and update the spec to allow JSON errors at the end.
 */
public class TravelTimeComputer {

    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeComputer.class);

    public final AnalysisTask request;
    public final TransportNetwork network;
    public final GridCache gridCache;

    public TravelTimeComputer(AnalysisTask request, TransportNetwork network, GridCache gridCache) {
        this.request = request;
        this.network = network;
        this.gridCache = gridCache;
    }

    /**
     * Merges two int-int maps, keeping the minimum value when keys collide.
     */
    private static void minMergeMap (TIntIntMap target, TIntIntMap source) {
       source.forEachEntry((key, val) -> {
            if (target.containsKey(key)) {
               int existingVal = target.get(key);
               if (val < existingVal) {
                   target.put(key, val);
               }
            } else {
               target.put(key, val);
            }
            return true;
       });

    }
    // We should try to decouple the internal representation of the results from how they're serialized to an API.

    /**
     * The TravelTimeComputer can make travel time grids, accessibility indicators, or (eventually) both
     * depending on what's in the task it's given. TODO factor out each major step of this process into private methods.
     */
    public OneOriginResult computeTravelTimes() {

        // 0. Preliminary range checking and setup =====================================================================
        if (!request.directModes.equals(request.accessModes)) {
            throw new IllegalArgumentException("Direct mode may not be different than access mode in Analysis.");
        }

        // If this request includes a fare calculator, inject the transport network's transit layer into it.
        // This is threadsafe because deserializing each incoming request creates a new fare calculator instance.
        if (request.inRoutingFareCalculator != null) {
            request.inRoutingFareCalculator.transitLayer = network.transitLayer;
        }

        // Convert from floating point meters per second (in request) to integer millimeters per second (internal).
        int walkSpeedMillimetersPerSecond = (int) (request.walkSpeed * MM_PER_METER);

        // Create an object that accumulates travel times at each destination, simplifying them into percentiles.
        // TODO Create and encapsulate this within the propagator.
        TravelTimeReducer travelTimeReducer = new TravelTimeReducer(request);

        // Determine car pick-up delay time for the access leg, which is generally specified in a scenario modification.
        // Negative values mean no car service is available.
        // Only find this time when cars are in use, as it requires potentially slow geometry operations.
        final int carPickupDelaySeconds = (request.accessModes.contains(LegMode.CAR)) ?
            network.streetLayer.getWaitTime(request.fromLat, request.fromLon) : 0;

        // Find the set of destinations for a one-to-many travel time calculation, not yet linked to the street network.
        // By finding the extents and destinations up front, we ensure the exact same grid is used for all steps below.
        // This reuses the logic for finding the appropriate grid size and linking, which is now in the NetworkPreloader.
        // We could change the preloader to retain these values in a compound return type, to avoid repetition here.
        // TODO merge multiple destination pointsets from a regional request into a single supergrid?
        WebMercatorExtents destinationGridExtents = NetworkPreloader.Key.forTask(request).webMercatorExtents;
        PointSet destinations = AnalysisTask.gridPointSetCache.get(destinationGridExtents, network.gridPointSet);

        // I. Direct no-transit routing ================================================================================
        // Handle the special case where the search will not use transit at all.
        // NOTE: Currently this is the only case where we use directModes, which is required to be the same as accessModes.

        if (request.transitModes.isEmpty()) {
            // TODO handle direct modes in the same loop that handles access, maybe by factoring it out into a method.
            StreetMode directMode = LegMode.getDominantStreetMode(request.directModes);
            // If there is no car pickup service, skip routing (don't even try walking).
            if (directMode == StreetMode.CAR && carPickupDelaySeconds < 0) {
                return travelTimeReducer.finish();
            }
            // When doing a non-transit walk search, we're not trying to match the behavior of egress and transfer
            // searches which use distance as the quantity to minimize (because they are precalculated and stored as
            // distance, and then converted to times by dividing by speed without regard to weights/penalties for things
            // like stairs). This does mean that walk-only results will not match the walking portion of walk+transit results.
            StreetRouter sr = new StreetRouter(network.streetLayer);
            sr.profileRequest = request;
            sr.timeLimitSeconds = request.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE;
            sr.streetMode = directMode;
            // NOTE direct mode is limited only by the full travel duration, rather than the individual per-mode limits
            sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            sr.route();

            int speedMillimetersPerSecond = (int) (request.getSpeedForMode(directMode) * 1000);

            LinkedPointSet directModeLinkedDestinations = destinations.getLinkage(network.streetLayer, directMode);
            int[] travelTimesToTargets = directModeLinkedDestinations
                    .eval(sr::getTravelTimeToVertex, speedMillimetersPerSecond, walkSpeedMillimetersPerSecond).travelTimes;

            // Iterate over all destinations ("targets") and at each destination, save the same travel time for all percentiles.
            for (int d = 0; d < travelTimesToTargets.length; d++) {
                final int travelTimeSeconds = travelTimesToTargets[d] + carPickupDelaySeconds;
                travelTimeReducer.recordTravelTimesForTarget(d, new int[]{travelTimeSeconds});
            }

            // TODO if we allow multiple directModes, check the next fastest mode. Walking or biking might be faster,
            //  especially if there are congestion or pickup delay modifications.

            return travelTimeReducer.finish();
        }

        // II. Access to transit =======================================================================================
        // If we fall through to here, transit modes were specified in the request.
        // We want to use one or more modes to access transit stops, retaining the reached transit stops as well as the
        // travel times to the destination points using those access modes.

        // A map from transit stop vertex indices to the travel time it takes to reach those vertices in seconds.
        TIntIntMap accessTimes = new TIntIntHashMap();

        // Travel times in seconds to each destination point (or MAX_INT for unreachable points?)
        // Starts out as null but will be updated when any access leg search succeeds.
        PointSetTimes nonTransitTravelTimesToDestinations = null;

        // We may try to link to the street network and perform an access search with several modes.
        // This tracks whether any of those searches could even be linked to the street network.
        boolean foundAnyOriginPoint = false;

        // Convert from profile routing qualified modes to internal modes
        EnumSet<StreetMode> accessModes = LegMode.toStreetModeSet(request.accessModes);

        // TODO make iteration over access modes conditional on use of transit? Include direct modes?
        for (StreetMode accessMode : accessModes) {
            LOG.info("Performing street search for mode: {}", accessMode);
            // TODO rename to modeSpeedMillimetersPerSecond
            int streetSpeedMillimetersPerSecond =  (int) (request.getSpeedForMode(accessMode) * 1000);
            if (streetSpeedMillimetersPerSecond <= 0){
                throw new IllegalArgumentException("Speed of access mode must be greater than 0.");
            }
            if (accessMode == StreetMode.CAR && carPickupDelaySeconds < 0) {
                LOG.info("Car pick-up service is not available at this location, continuing to next access mode (if any).");
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

            // The code blocks below essentially serve to identify transit stations reachable from the origin and
            // produce a grid of non-transit travel times that will later be merged with the transit travel times.
            // Note: this is essentially the same thing that is happening when creating linkage cost tables for the
            // egress end of the trip. We could probably reuse a method for both (getTravelTimesFromPoint).

            // Note: Access searches (minimizing time) are asymmetric with the egress cost tables (often minimizing
            // distance to allow reuse at different speeds).
            sr.timeLimitSeconds = request.getMaxTimeSeconds(accessMode);
            sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            sr.route();
            // Change to walking in order to reach transit stops in pedestrian-only areas like train stations.
            // This implies you are dropped off or have a very easy parking spot for your vehicle.
            // This kind of multi-stage search should probably also be used when building egress distance cost tables.
            if (accessMode != StreetMode.WALK) {
                sr.keepRoutingOnFoot();
            }
            TIntIntMap travelTimesToStopsSeconds = sr.getReachedStops();

            LinkedPointSet linkedDestinations = destinations.getLinkage(network.streetLayer, accessMode);
            // FIXME this is iterating over every cell in the (possibly huge) destination grid just to get the access times around the origin.
            PointSetTimes pointSetTimes = linkedDestinations.eval(sr::getTravelTimeToVertex,
                    streetSpeedMillimetersPerSecond, walkSpeedMillimetersPerSecond);

            if (accessMode == StreetMode.CAR && carPickupDelaySeconds > 0) {
                LOG.info("Delaying access times by {} seconds (for car pick-up wait).", carPickupDelaySeconds);
                travelTimesToStopsSeconds.transformValues(i -> i + carPickupDelaySeconds);
                pointSetTimes.incrementAllReachable(carPickupDelaySeconds);
            }
            minMergeMap(accessTimes, travelTimesToStopsSeconds);
            nonTransitTravelTimesToDestinations = PointSetTimes.minMerge(nonTransitTravelTimesToDestinations, pointSetTimes);
        }

        /*
        // FIXME Special case to handle park+ride, a mode represented in the request LegMode but not in the internal StreetMode
        if (request.accessModes.contains(LegMode.CAR_PARK)) {
            // Currently first search from origin to P+R is hardcoded as time dominance variable for Max car time seconds
            // Second search from P+R to stops is not actually a search we just return list of all reached stops for each found P+R.
            // If multiple P+Rs reach the same stop, only one with shortest time is returned. Stops were searched for during graph building phase.
            // time to stop is time from CAR streetrouter to stop + CAR PARK time + time to walk to stop based on request walk speed
            // by default 20 CAR PARKS are found it can be changed with sr.maxVertices variable
            sr = PointToPointQuery.findParkRidePath(request, sr, network.transitLayer);

            if (sr == null) {
                // Origin not found. Return an empty access times map, as is done by the other conditions for other modes.
                // FIXME this is ugly. we should have a way to break out of the search early (here and in other methods).
                // It causes regional analyses to be very slow when there are a large number of disconnected cells.
                accessTimes = new TIntIntHashMap();
            } else {
                accessTimes = sr.getReachedStops();
            }

            // disallow non-transit access
            // TODO should we allow non transit access with park and ride?
            nonTransitTravelTimesToDestinations = new int[accessModeLinkedDestinations.size()];
            Arrays.fill(nonTransitTravelTimesToDestinations, FastRaptorWorker.UNREACHED);
        }
        */

        if (!foundAnyOriginPoint) {
            // The origin point was not even linked to the street network.
            // Calling finish() before streaming in any travel times to destinations is designed to produce the right result.
            LOG.info("Origin point was outside the street network. Skipping routing and propagation, and returning default result.");
            return travelTimeReducer.finish();
        }

        // Short circuit unnecessary transit routing: If the origin was linked to a road, but no transit stations
        // were reached, return the non-transit grid as the final result.
        // Should we combine this with the handling of situations where transit is not requested, and direct legs?
        if (accessTimes.isEmpty()) {  // || request.transitModes.isEmpty()
            LOG.info("Skipping transit search since no transit stops were reached.");
            for (int target = 0; target < nonTransitTravelTimesToDestinations.travelTimes.length; target++) {
                // TODO: pull this loop out into a method: travelTimeReducer.recordPointSetTimes(accessTimes)
                final int travelTimeSeconds = nonTransitTravelTimesToDestinations.getTravelTimeToPoint(target);
                travelTimeReducer.recordTravelTimesForTarget(target, new int[] { travelTimeSeconds });
            }
            return travelTimeReducer.finish();
        }

        // III. Transit Routing ========================================================================================
        // Transit stops were reached. Perform transit routing from those stops to all other reachable stops. The result
        // is a travel time in seconds for each iteration (departure time x monte carlo draw), for each transit stop.
        int[][] transitTravelTimesToStops;
        FastRaptorWorker worker = null;
        if (request.inRoutingFareCalculator == null) {
            worker = new FastRaptorWorker(network.transitLayer, request, accessTimes);
            if (request.returnPaths || request.travelTimeBreakdown) {
                // By default, this is false and intermediate results (e.g. paths) are discarded.
                // TODO do we really need to save all states just to get the travel time breakdown?
                worker.retainPaths = true;
            }
            // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
            // Returns the total travel times as a 2D array of [searchIteration][destinationStopIndex].
            // Additional detailed path information is retained in the FastRaptorWorker after routing.
            transitTravelTimesToStops = worker.route();
        } else {
            // TODO maxClockTime could provide a tighter bound, as it could be based on the actual departure time, not the last possible
            IntFunction<DominatingList> listSupplier =
                    (departureTime) -> new FareDominatingList(
                            request.inRoutingFareCalculator,
                            request.maxFare,
                            departureTime + request.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE);
            McRaptorSuboptimalPathProfileRouter mcRaptorWorker = new McRaptorSuboptimalPathProfileRouter(network,
                    request, null, null, listSupplier, InRoutingFareCalculator.getCollator(request));
            mcRaptorWorker.route();
            transitTravelTimesToStops = mcRaptorWorker.getBestTimes();
        }

        // IV. Egress Propagation ======================================================================================
        // Propagate these travel times for every iteration at every stop out to the destination points, via streets.

        // Prepare a set of modes, all of which will simultaneously be used for on-street egress.
        EnumSet<StreetMode> egressStreetModes = LegMode.toStreetModeSet(request.egressModes);

        // This propagator will link the destinations to the street layer for all modes as needed.
        PerTargetPropagater perTargetPropagater = new PerTargetPropagater(
                destinations,
                network.streetLayer,
                egressStreetModes,
                request,
                transitTravelTimesToStops,
                nonTransitTravelTimesToDestinations.travelTimes);

        // We cannot yet merge the functionality of the TravelTimeReducer into the PerTargetPropagator
        // because in the non-transit case we call the reducer directly (see above).
        perTargetPropagater.travelTimeReducer = travelTimeReducer;

        // When building a static site, perform some additional initialization causing the propagator to do extra work.
        if (request.returnPaths || request.travelTimeBreakdown) {
            perTargetPropagater.pathsToStopsForIteration = worker.pathsPerIteration;
            perTargetPropagater.pathWriter = new PathWriter(request);
        }

        return perTargetPropagater.propagate();

    }

}
