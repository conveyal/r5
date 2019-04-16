package com.conveyal.r5.analyst;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.PathWriter;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.DominatingList;
import com.conveyal.r5.profile.FareDominatingList;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.IntFunction;

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

    // We should try to decouple the internal representation of the results from how they're serialized to an API.

    /**
     * The TravelTimeComputer can make travel time grids, accessibility indicators, or (eventually) both
     * depending on what's in the task it's given.
     */
    public OneOriginResult computeTravelTimes() {

        // If this request includes a fare calculator, inject the transport network's transit layer into it.
        // This is threadsafe because deserializing each incoming request creates a new fare calculator instance.
        if (request.inRoutingFareCalculator != null) {
            request.inRoutingFareCalculator.transitLayer = network.transitLayer;
        }

        // The mode of travel that will be used to reach transit stations from the origin point.
        StreetMode accessMode = LegMode.getDominantStreetMode(request.accessModes);
        // The mode of travel that will be used to reach destinations from transit stations.
        StreetMode egressMode = LegMode.getDominantStreetMode(request.egressModes);
        // The mode of travel that would be used to reach the destination directly without using transit.
        StreetMode directMode = LegMode.getDominantStreetMode(request.directModes);

        // The request has the speed in float meters per second, internally we use integer millimeters per second.
        int streetSpeedMillimetersPerSecond = (int) (request.getSpeedForMode(accessMode) * 1000);

        int walkSpeedMillimetersPerSecond = (int) (request.walkSpeed * 1000);

        // Find the set of destinations in the one-to-many travel time calculations, not yet linked to the street network.
        // Reuse the logic for finding the appropriate grid size and linking, which is now in the NetworkPreloader.
        // We could change the preloader to retain these values in a compound return type, to avoid repetition here.
        WebMercatorExtents destinationGridExtents = NetworkPreloader.Key.forTask(request).webMercatorExtents;
        // TODO wrap in loop to repeat for multiple destinations pointsets in a regional request.
        PointSet destinations = AnalysisTask.gridPointSetCache.get(destinationGridExtents, network.gridPointSet);

        // TODO Create and encapsulate this within the propagator.
        TravelTimeReducer travelTimeReducer = new TravelTimeReducer(request);

        // Attempt to set the origin point before progressing any further.
        // This allows us to skip routing calculations if the network is entirely inaccessible. In the CAR_PARK
        // case this StreetRouter will be replaced but this still serves to bypass unnecessary computation.
        // The request must be provided to the StreetRouter before setting the origin point.
        StreetRouter sr = new StreetRouter(network.streetLayer);
        sr.profileRequest = request;
        sr.streetMode = accessMode;
        boolean foundOriginPoint = sr.setOrigin(request.fromLat, request.fromLon);
        if (!foundOriginPoint) {
            // Short circuit around routing and propagation. Calling finish() before streaming in any travel times to
            // destinations is designed to produce the right result.
            LOG.info("Origin point was outside the transport network. Skipping routing and propagation, and returning default result.");
            return travelTimeReducer.finish();
        }

        // First we will find travel times to all destinations reachable without using transit.
        // Simultaneously we will find stations that allow access to the transit network.
        if (request.transitModes.isEmpty()) {
            // This search will use no transit.
            //
            // When doing a non-transit walk search, we're not trying to match the behavior of egress and transfer
            // searches which use distance as the quantity to minimize (because they are precalculated and stored as distance,
            // and then converted to times by dividing by speed without regard to weights/penalties for things like stairs).
            // This does mean that walk-only results will not match the walking portion of walk+transit results.
            sr.timeLimitSeconds = request.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE;
            sr.streetMode = directMode;
            sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            sr.route();

            int speedMillimetersPerSecond = (int) (request.getSpeedForMode(directMode) * 1000);

            LinkedPointSet directModeLinkedDestinations = destinations.getLinkage(network.streetLayer, directMode);
            int[] travelTimesToTargets = directModeLinkedDestinations
                    .eval(sr::getTravelTimeToVertex, speedMillimetersPerSecond, walkSpeedMillimetersPerSecond).travelTimes;

            // Iterate over all destinations ("targets") and at each destination, save the same travel time for all percentiles.
            for (int d = 0; d < travelTimesToTargets.length; d++) {
                final int travelTimeSeconds = travelTimesToTargets[d];
                travelTimeReducer.recordTravelTimesForTarget(d, new int[] { travelTimeSeconds });
            }
            return travelTimeReducer.finish();
        } else {
            // This search will include transit.
            //
            // If the access and egress modes are both the same, the pointset's linkage cache will return two
            // references to the same linkage.
            // TODO use directMode? Is that a resource limiting issue?
            // Also, gridcomputer uses accessMode to avoid running two street searches.
            LinkedPointSet accessModeLinkedDestinations = destinations.getLinkage(network.streetLayer, accessMode);
            LinkedPointSet egressModeLinkedDestinations = destinations.getLinkage(network.streetLayer, egressMode);

            if (!request.directModes.equals(request.accessModes)) {
                LOG.error("Direct mode may not be different than access mode in analysis.");
            }

            // The code blocks below essentially serve to identify transit stations reachable from the origin and
            // produce a grid of non-transit travel times that will later be merged with the transit travel times.

            // A map from transit stop vertex indices to the travel time it takes to reach those vertices.
            TIntIntMap accessTimes;

            // This will hold the travel times to all destination grid cells reachable without using transit
            // (via only the access/direct mode).
            int[] nonTransitTravelTimesToDestinations;

            if (streetSpeedMillimetersPerSecond <= 0){
                throw new IllegalArgumentException("Speed of access/direct modes must be greater than 0.");
            }

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
            } else if (accessMode == StreetMode.WALK) {
                // Special handling for walk search: find distance in millimeters and divide by speed to match behavior
                // at egress (in stop to point linkage cost tables). For bike/car searches this is immaterial as the
                // access searches are already asymmetric (i.e. bike/car linkage distances precomputed for egress
                // can't be used for access, because there are one-way streets; we ignore the possibility of one-way
                // pedestrian ways).
                sr.distanceLimitMeters =
                        (int) (request.walkSpeed * request.maxWalkTime * FastRaptorWorker.SECONDS_PER_MINUTE);
                sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
                sr.route();

                // Get the travel times to all stops reached in the initial on-street search.
                // getReachedStops returns distances in this case because quantityToMinimize is millimeters;
                // convert these distances to times in the loop below.
                accessTimes = sr.getReachedStops();
                for (TIntIntIterator it = accessTimes.iterator(); it.hasNext(); ) {
                    it.advance();
                    it.setValue(it.value() / walkSpeedMillimetersPerSecond);
                }

                // again, use distance / speed rather than time for symmetry with other searches
                final StreetRouter effectivelyFinalSr = sr;
                // FIXME is this iterating over every cell in the destination grid just to get the access times around the origin?
                nonTransitTravelTimesToDestinations =
                        accessModeLinkedDestinations.eval(v -> {
                                    StreetRouter.State state = effectivelyFinalSr.getStateAtVertex(v);
                                    if (state == null) return FastRaptorWorker.UNREACHED;
                                    else return state.distance / streetSpeedMillimetersPerSecond;
                                },
                                walkSpeedMillimetersPerSecond,
                                walkSpeedMillimetersPerSecond).travelTimes;
            } else {
                // Other modes are already asymmetric with the egress/stop trees, so just do a time-based on street
                // search and don't worry about distance limiting.
                sr.timeLimitSeconds = request.getMaxAccessTimeForMode(accessMode) * FastRaptorWorker.SECONDS_PER_MINUTE;
                sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
                sr.route();
                accessTimes = sr.getReachedStops(); // already in seconds
                nonTransitTravelTimesToDestinations =
                        accessModeLinkedDestinations.eval(sr::getTravelTimeToVertex, streetSpeedMillimetersPerSecond,
                                walkSpeedMillimetersPerSecond).travelTimes;
            }

            // Short circuit unnecessary transit routing: If the origin was linked to a road, but no transit stations
            // were reached, return the non-transit grid as the final result.
            if (accessTimes.isEmpty()) {
                LOG.info("Skipping transit search since no transit stops were reached.");
                for (int target = 0; target < nonTransitTravelTimesToDestinations.length; target++) {
                    // TODO abstraction for travel time grid, with method to write it directly to reducer
                    final int travelTimeSeconds = nonTransitTravelTimesToDestinations[target];
                    travelTimeReducer.recordTravelTimesForTarget(target, new int[] { travelTimeSeconds });
                }
                return travelTimeReducer.finish();
            }

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
            PerTargetPropagater perTargetPropagater = new PerTargetPropagater(egressModeLinkedDestinations, request,
                    transitTravelTimesToStops, nonTransitTravelTimesToDestinations);

            // We cannot yet merge the functionality of the TravelTimeReducer into the PerTargetPropagator
            // because in the non-transit case we call the reducer directly (see above).
            perTargetPropagater.travelTimeReducer = travelTimeReducer;

            if (request.returnPaths || request.travelTimeBreakdown) {
                perTargetPropagater.pathsToStopsForIteration = worker.pathsPerIteration;
                perTargetPropagater.pathWriter = new PathWriter(request);
            }

            return perTargetPropagater.propagate();
        }
    }
}
