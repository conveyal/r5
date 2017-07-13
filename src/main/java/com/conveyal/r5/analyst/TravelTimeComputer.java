package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisRequest;
import com.conveyal.r5.analyst.cluster.AnalystWorker;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;

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
    private static final WebMercatorGridPointSetCache pointSetCache = new WebMercatorGridPointSetCache();

    public final AnalysisRequest request;
    public final TransportNetwork network;
    public final GridCache gridCache;

    public TravelTimeComputer(AnalysisRequest request, TransportNetwork network, GridCache gridCache) {
        this.request = request;
        this.network = network;
        this.gridCache = gridCache;
    }

    public void write (OutputStream os) throws IOException {
        StreetMode accessMode = LegMode.getDominantStreetMode(request.accessModes);
        StreetMode directMode = LegMode.getDominantStreetMode(request.directModes);

        // use x, y within grid if available, otherwise fromLat and toLat
        double fromLat = request.fromLat;
        double fromLon = request.fromLon;
        if (request.x >= 0 && request.y >= 0) {
            fromLat = request.y >= 0 ? Grid.pixelToCenterLat(request.y + request.north, request.zoom) : request.fromLat;
            fromLon = request.x >= 0 ? Grid.pixelToCenterLon(request.x + request.west, request.zoom) : request.fromLon;
        } else if (request.x >= 0 || request.y >= 0) {
            throw new IllegalArgumentException("If either x or y coordinate of origin is specified, both must be specified.");
        }

        WebMercatorGridPointSet destinations;
        if (request.grid != null) {
            // NB kinda hacky, grids are only used when doing regional analysis as accessibility is computed client side
            // in interactive single point mode. But we need to know how big the grid is to know how big to make the
            // destination pointset.
            // This does mean two calls to gridCache.get (here and in BootstrappingTravelTimeReducer), but gridCache is a true
            // LoadingCache so this should not matter as the grid should already be loaded and the same one returned on
            // both calls.
            // Note that it doesn't matter if the origin is outside of the grid size. Since it is assumed there are no
            // opportunities outside the grid, only the cells within the grid can have any effect, so there is no need to
            // link additional cells.
            Grid grid = gridCache.get(request.grid);
            destinations = pointSetCache.get(grid);

        } else {
            destinations = pointSetCache.get(request.zoom, request.west, request.north, request.width, request.height);
        }

        PerTargetPropagater.TravelTimeReducer output;
        if (request.type == AnalysisRequest.Type.TRAVEL_TIME_SURFACE) output = new TravelTimeSurfaceReducer(request, network, os);
        else if (request.type == AnalysisRequest.Type.REGIONAL_ANALYSIS) output = new BootstrappingTravelTimeReducer(request, gridCache);
        else throw new IllegalArgumentException("Unrecognized request type " + request.type);

        if (request.transitModes.isEmpty()) {
            // non transit search
            StreetRouter sr = new StreetRouter(network.streetLayer);
            sr.timeLimitSeconds = request.maxTripDurationMinutes * 60;
            sr.streetMode = directMode;
            sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            sr.profileRequest = request;
            sr.setOrigin(fromLat, fromLon);
            sr.route();

            int offstreetTravelSpeedMillimetersPerSecond = (int) (request.getSpeedForMode(directMode) * 1000);

            LinkedPointSet linkedDestinations = destinations.link(network.streetLayer, directMode);

            int[] travelTimesToTargets = linkedDestinations.eval(sr::getTravelTimeToVertex, offstreetTravelSpeedMillimetersPerSecond).travelTimes;
            for (int target = 0; target < travelTimesToTargets.length; target++) {
                int x = target % request.width;
                int y = target / request.width;

                final int travelTimeMinutes =
                        travelTimesToTargets[target] == FastRaptorWorker.UNREACHED ? FastRaptorWorker.UNREACHED : travelTimesToTargets[target] / 60;

                output.accept(y * request.width + x, new int[] { travelTimeMinutes });
            }

            output.finish();
        } else {
            // we always walk to egress from transit, but we may have a different access mode.
            // if the access mode is also walk, the pointset's linkage cache will return two references to the same
            // linkage below
            // TODO use directMode? Is that a resource limiting issue?
            // also gridcomputer uses accessMode to avoid running two street searches
            LinkedPointSet linkedDestinationsAccess = destinations.link(network.streetLayer, accessMode);
            LinkedPointSet linkedDestinationsEgress = destinations.link(network.streetLayer, StreetMode.WALK);

            if (!request.directModes.equals(request.accessModes)) {
                LOG.warn("Disparate direct modes and access modes are not supported in analysis mode.");
            }

            // Perform street search to find transit stops and non-transit times.
            StreetRouter sr = new StreetRouter(network.streetLayer);
            sr.streetMode = accessMode;
            sr.profileRequest = request;
            sr.distanceLimitMeters = 2000; // TODO hardwired same as gridcomputer
            sr.setOrigin(fromLat, fromLon);
            sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
            sr.route();

            // Get the travel times to all stops reached in the initial on-street search. Convert distances to speeds.
            int offstreetTravelSpeedMillimetersPerSecond = (int) (request.getSpeedForMode(accessMode) * 1000);

            // getReachedStops returns distances, not times, so convert to times in the loop below
            TIntIntMap accessTimes = sr.getReachedStops();
            for (TIntIntIterator it = accessTimes.iterator(); it.hasNext(); ) {
                it.advance();
                // TODO how to handle kiss and ride/park and ride? Clearly this is not right.
                it.setValue(it.value() / offstreetTravelSpeedMillimetersPerSecond);
            }

            // Create a new Raptor Worker.
            FastRaptorWorker worker = new FastRaptorWorker(network.transitLayer, request, accessTimes);

            // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
            int[][] transitTravelTimesToStops = worker.route();

            // find non-transit times
            int[] nonTransitTravelTimesToDestinations = linkedDestinationsAccess
                    .eval(sr::getTravelTimeToVertex, offstreetTravelSpeedMillimetersPerSecond)
                    .travelTimes;

            // From this point on the requests are handled separately depending on whether

            PerTargetPropagater perTargetPropagater = new PerTargetPropagater(transitTravelTimesToStops, nonTransitTravelTimesToDestinations, linkedDestinationsEgress, request, 120 * 60);
            perTargetPropagater.propagate(output);
        }
    }
}
