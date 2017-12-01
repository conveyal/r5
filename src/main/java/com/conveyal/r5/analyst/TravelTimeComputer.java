package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.FastRaptorWorker;
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

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

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

    // FIXME pointset cache is never used - why?
    private static final WebMercatorGridPointSetCache pointSetCache = new WebMercatorGridPointSetCache();

    public final AnalysisTask request;
    public final TransportNetwork network;
    public final GridCache gridCache;

    public TravelTimeComputer(AnalysisTask request, TransportNetwork network, GridCache gridCache) {
        this.request = request;
        this.network = network;
        this.gridCache = gridCache;
    }

    // TODO rename this - "writing" is only a minor side effect of what it's doing.
    // Perhaps function should not be void return type. Why is this using a streaming or pipelining approach?
    // We also want to decouple the internal representation of the results from how they're serialized to an API.
    public void write (OutputStream os) throws IOException {

        // The mode of travel that will be used to reach transit stations from the origin point.
        StreetMode accessMode = LegMode.getDominantStreetMode(request.accessModes);
        // The mode of travel that will be used to reach destinations from transit stations.
        StreetMode egressMode = LegMode.getDominantStreetMode(request.egressModes);
        // The mode of travel that would be used to reach the destination directly without using transit.
        StreetMode directMode = LegMode.getDominantStreetMode(request.directModes);

        // The set of destinations in the one-to-many travel time calculations, not yet linked to the street network.
        PointSet destinations = request.getDestinations(network, gridCache);

        // Get the appropriate function for reducing travel time, given the type of request we're handling
        // (either a travel time surface for a single point or a location based accessibility indicator value for a
        // regional analysis).
        // FIXME maybe the reducer function should just be defined (overridden) on the request class.
        // FIXME the reducer is given the output stream in a pseudo-pipelining approach. However it just accumulates results into memory before writing them out.
        // Also, some of these classes could probably just be static functions.
        PerTargetPropagater.TravelTimeReducer travelTimeReducer = request.getTravelTimeReducer(network, os);

        // Attempt to set the origin point before progressing any further.
        // This allows us to short circuit calculations if the network is entirely inaccessible. In the CAR_PARK
        // case this StreetRouter will be replaced but this still serves to bypass unnecessary computation.
        StreetRouter sr = new StreetRouter(network.streetLayer);
        // Request must be provided to the router before setting the origin point.
        sr.profileRequest = request;
        sr.streetMode = accessMode;
        boolean foundOriginPoint = sr.setOrigin(request.fromLat, request.fromLon);
        if (!foundOriginPoint) {
            // Short circuit around routing and propagation.
            // Calling finish() before streaming in any travel times to destinations is designed to produce the right result here.
            LOG.info("Origin point was outside the transport network. Skipping routing and propagation, and returning default result.");
            travelTimeReducer.finish();
            return;
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
            sr.timeLimitSeconds = request.maxTripDurationMinutes * 60;
            sr.streetMode = directMode;
            sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            sr.route();

            int offstreetTravelSpeedMillimetersPerSecond = (int) (request.getSpeedForMode(directMode) * 1000);

            LinkedPointSet directModeLinkedDestinations = destinations.link(network.streetLayer, directMode);
            int[] travelTimesToTargets = directModeLinkedDestinations
                    .eval(sr::getTravelTimeToVertex, offstreetTravelSpeedMillimetersPerSecond).travelTimes;

            // FIXME replace with nested x and y loops, we are dividing then re-multiplying below
            for (int target = 0; target < travelTimesToTargets.length; target++) {
                int x = target % request.width;
                int y = target / request.width;
                final int travelTimeSeconds = travelTimesToTargets[target];
                travelTimeReducer.recordTravelTimesForTarget(y * request.width + x, new int[] { travelTimeSeconds });
            }

            travelTimeReducer.finish();
        } else {
            // This search will include transit.
            //
            // If the access and egress modes are both the same, the pointset's linkage cache will return two
            // references to the same linkage.
            // TODO use directMode? Is that a resource limiting issue?
            // Also, gridcomputer uses accessMode to avoid running two street searches.
            LinkedPointSet accessModeLinkedDestinations = destinations.link(network.streetLayer, accessMode);
            LinkedPointSet egressModeLinkedDestinations = destinations.link(network.streetLayer, egressMode);

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

            // The request has the speed in float meters per second, internally we use integer millimeters per second.
            int offstreetTravelSpeedMillimetersPerSecond = (int) (request.getSpeedForMode(accessMode) * 1000);

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
                // Special handling for walk search, find distance in seconds and divide to match behavior at egress
                // (in stop trees). For bike/car searches this is immaterial as the access searches are already asymmetric.
                // TODO clarify - I think this is referring to the fact that the egress trees are pre-calculated for a standard speed and must be adjusted.
                sr.distanceLimitMeters = 2000; // TODO hardwired same as gridcomputer, at least use a symbolic constant
                sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
                sr.route();

                // Get the travel times to all stops reached in the initial on-street search. Convert distances to speeds.
                // getReachedStops returns distances in this case since dominance variable is millimeters,
                // so convert to times in the loop below.
                accessTimes = sr.getReachedStops();
                for (TIntIntIterator it = accessTimes.iterator(); it.hasNext(); ) {
                    it.advance();
                    it.setValue(it.value() / offstreetTravelSpeedMillimetersPerSecond);
                }

                // again, use distance / speed rather than time for symmetry with other searches
                final StreetRouter effectivelyFinalSr = sr;
                // FIXME is this iterating over every cell in the destination grid just to get the access times around the origin?
                nonTransitTravelTimesToDestinations =
                        accessModeLinkedDestinations.eval(v -> {
                                    StreetRouter.State state = effectivelyFinalSr.getStateAtVertex(v);
                                    if (state == null) return FastRaptorWorker.UNREACHED;
                                    else return state.distance / offstreetTravelSpeedMillimetersPerSecond;
                                },
                                offstreetTravelSpeedMillimetersPerSecond).travelTimes;
            } else {
                // Other modes are already asymmetric with the egress/stop trees, so just do a time-based on street
                // search and don't worry about distance limiting.
                sr.timeLimitSeconds = request.getMaxAccessTimeForMode(accessMode) * 60;
                sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
                sr.route();
                accessTimes = sr.getReachedStops(); // already in seconds
                nonTransitTravelTimesToDestinations =
                        accessModeLinkedDestinations.eval(sr::getTravelTimeToVertex, offstreetTravelSpeedMillimetersPerSecond)
                                .travelTimes;
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
                travelTimeReducer.finish();
                return;
            }

            // Create a new Raptor Worker.
            FastRaptorWorker worker = new FastRaptorWorker(network.transitLayer, request, accessTimes);

            // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
            int[][] transitTravelTimesToStops = worker.route();

            // From this point on the requests are handled separately depending on whether they are single point requests
            // returning a surface representing the distribution of potential travel times to each destination, regional
            // requests that will return bootstrapped accessibility numbers via Amazon SQS.

            // FIXME this is a very Javascript-Lisp style functional approach, not really idiomatic Java.
            // We've also got methods tail-calling the next step in a process, instead of a method higher on the stack chaining them together.
            // We should probably set the reducer field on the propagator instance, and give the methods return values.
            // Actually these could just be two subclasses of the propagator with different TravelTimeReducer definition.
            PerTargetPropagater perTargetPropagater = new PerTargetPropagater(transitTravelTimesToStops, nonTransitTravelTimesToDestinations, egressModeLinkedDestinations, request, 120 * 60);
            perTargetPropagater.propagate(travelTimeReducer);
        }
    }
}
