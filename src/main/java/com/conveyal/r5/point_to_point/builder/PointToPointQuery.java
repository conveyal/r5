package com.conveyal.r5.point_to_point.builder;

import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.ProfileOption;
import com.conveyal.r5.api.util.StreetSegment;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.*;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripFlag;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Class which will make point to point or profile queries on Transport network based on profileRequest
 * Created by mabu on 23.12.2015.
 */
public class PointToPointQuery {
    private static final Logger LOG = LoggerFactory.getLogger(PointToPointQuery.class);

    /**
     * The largest number of stops to consider boarding at. If there are 1000 stops within 2km, only consider boarding at the closest 200.
     *
     * It's not clear this has a major effect on speed, so we could consider removing it.
     */
    private static final int MAX_ACCESS_STOPS = 200;

    private final TransportNetwork transportNetwork;

    // interpretation of below parameters: if biking is less than BIKE_PENALTY seconds faster than walking, we prefer to walk

    /** how many seconds worse biking to transit is than walking */
    private static final int BIKE_PENALTY = 600;

    /** how many seconds worse bikeshare is than just walking */
    private static final int BIKESHARE_PENALTY = 300;

    /** How many seconds worse driving to transit is than just walking */
    private static final int CAR_PENALTY = 1200;

    private static final EnumSet<LegMode> currentlyUnsupportedModes = EnumSet.of(LegMode.CAR_PARK);

    /** Time to rent a bike in seconds */
    private static final int BIKE_RENTAL_PICKUP_TIME_S = 60;
    /**
     * Cost of renting a bike. The cost is a bit more than actual time to model the associated cost and trouble.
     */
    private static final int BIKE_RENTAL_PICKUP_COST = 120;
    /** Time to drop-off a rented bike in seconds */
    private static final int BIKE_RENTAL_DROPOFF_TIME_S = 30;
    /** Cost of dropping-off a rented bike */
    private static final int BIKE_RENTAL_DROPOFF_COST = 30;
    /** Time to park car in P+R in seconds **/
    private static final int CAR_PARK_DROPOFF_TIME_S = 120;

    private static final int CAR_PARK_DROPOFF_COST = 120;

    public PointToPointQuery(TransportNetwork transportNetwork) {
        this.transportNetwork = transportNetwork;
    }

    public ZoneId getTimezone() {
        return this.transportNetwork.getTimeZone();
    }

    //Does point to point routing with data from request
    public ProfileResponse getPlan(ProfileRequest request) {
        request.zoneId = transportNetwork.getTimeZone();
        //Do the query and return result
        ProfileResponse profileResponse = new ProfileResponse();

        boolean transit = request.hasTransit();

        TaskStatistics ts = new TaskStatistics();

        EnumSet<LegMode> modes = transit ? request.accessModes : request.directModes;
        ProfileOption option = new ProfileOption();

        Map<LegMode, StreetRouter> accessRouter = new HashMap<>(modes.size());
        Map<LegMode, StreetRouter> egressRouter = new HashMap<>(request.egressModes.size());

        //This map saves which access mode was used to access specific stop in access mode
        TIntObjectMap<LegMode> stopModeAccessMap = new TIntObjectHashMap<>();
        //This map saves which egress mode was used to access specific stop in egress mode
        TIntObjectMap<LegMode> stopModeEgressMap = new TIntObjectHashMap<>();

        //Routes all direct (if no transit)/access modes
        for(LegMode mode: modes) {
            long initialStopStartTime = System.currentTimeMillis();
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
            StreetPath streetPath;
            streetRouter.profileRequest = request;
            if (mode == LegMode.CAR_PARK && !transit) {
                LOG.warn("Can't search for P+R without transit");
                continue;
            }
            if (mode == LegMode.CAR_PARK) {
                streetRouter = findParkRidePath(request, streetRouter);
                if (streetRouter != null) {
                    accessRouter.put(LegMode.CAR_PARK, streetRouter);
                    ts.initialStopSearch += (int) (System.currentTimeMillis() - initialStopStartTime);
                } else {
                    LOG.warn(
                        "MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!",
                        mode);
                }
                continue;
            } else if (mode == LegMode.BICYCLE_RENT) {
                if (!transportNetwork.streetLayer.bikeSharing) {
                    LOG.warn("Bike sharing trip requested but no bike sharing stations in the streetlayer");
                    continue;
                }
                streetRouter = findBikeRentalPath(request, streetRouter);
                if (streetRouter != null) {
                    if (transit) {
                        accessRouter.put(LegMode.BICYCLE_RENT, streetRouter);
                        continue;
                    } else {
                        StreetRouter.State lastState = streetRouter.getState(request.toLat, request.toLon);
                        if (lastState != null) {
                            streetPath = new StreetPath(lastState, streetRouter, LegMode.BICYCLE_RENT, transportNetwork);

                        } else {
                            LOG.warn("MODE:{}, Edge near the destination coordinate wasn't found. Routing didn't start!", mode);
                            continue;
                        }
                    }
                } else {
                    LOG.warn("Not found path from cycle to end");
                    continue;
                }
            } else {
                //TODO: add support for bike sharing and park and ride
                streetRouter.streetMode = StreetMode.valueOf(mode.toString());
                // TODO add time and distance limits to routing, not just weight.
                // TODO apply walk and bike speeds and maxBike time.
                streetRouter.distanceLimitMeters = transit ? 2000 : 100_000; // FIXME arbitrary, and account for bike or car access mode
                if(streetRouter.setOrigin(request.fromLat, request.fromLon)) {
                    streetRouter.route();
                     //Searching for access paths
                    if (transit) {
                        //TIntIntMap stops = streetRouter.getReachedStops();
                        //reachedTransitStops.putAll(stops);
                        //LOG.info("Added {} stops for mode {}",stops.size(), mode);
                        accessRouter.put(mode, streetRouter);
                        ts.initialStopSearch += (int) (System.currentTimeMillis() - initialStopStartTime);
                        continue;
                    //Searching for direct paths
                    } else{
                        StreetRouter.State lastState = streetRouter.getState(request.toLat, request.toLon);
                        if (lastState != null) {
                            streetPath = new StreetPath(lastState, transportNetwork);
                        } else {
                            LOG.warn("MODE:{}, Edge near the end coordinate wasn't found. Routing didn't start!", mode);
                            continue;
                        }

                    }
                } else {
                    LOG.warn("MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!", mode);
                    continue;
                }
            }
            StreetSegment streetSegment = new StreetSegment(streetPath, mode,
                transportNetwork.streetLayer);
            option.addDirect(streetSegment, request.getFromTimeDateZD());

        }
        if (transit) {
            //For direct modes
            for(LegMode mode: request.directModes) {
                StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
                StreetPath streetPath;
                streetRouter.profileRequest = request;
                if (mode == LegMode.BICYCLE_RENT) {
                    if (!transportNetwork.streetLayer.bikeSharing) {
                        LOG.warn("Bike sharing trip requested but no bike sharing stations in the streetlayer");
                        continue;
                    }
                    streetRouter = findBikeRentalPath(request, streetRouter);
                    if (streetRouter != null) {
                        StreetRouter.State lastState = streetRouter.getState(request.toLat, request.toLon);
                        if (lastState != null) {
                            streetPath = new StreetPath(lastState, streetRouter, LegMode.BICYCLE_RENT, transportNetwork);

                        } else {
                            LOG.warn("MODE:{}, Edge near the destination coordinate wasn't found. Routing didn't start!", mode);
                            continue;
                        }
                    } else {
                        LOG.warn("Not found path from cycle to end");
                        continue;
                    }
                } else {
                    streetRouter.streetMode = StreetMode.valueOf(mode.toString());
                    streetRouter.distanceLimitMeters = 100_000; // FIXME arbitrary, and account for bike or car access mode
                    if(streetRouter.setOrigin(request.fromLat, request.fromLon)) {
                        streetRouter.setDestination(request.toLat, request.toLon);
                        streetRouter.route();
                        StreetRouter.State lastState = streetRouter.getState(streetRouter.getDestinationSplit());
                        if (lastState == null) {
                            LOG.warn("Direct mode {} last state wasn't found", mode);
                            continue;
                        }
                        streetPath = new StreetPath(lastState, transportNetwork);
                    } else {
                        LOG.warn("Direct mode {} origin wasn't found!", mode);
                        continue;
                    }
                }

                StreetSegment streetSegment = new StreetSegment(streetPath, mode,
                    transportNetwork.streetLayer);
                option.addDirect(streetSegment, request.getFromTimeDateZD());
            }

            //For egress
            //TODO: this must be reverse search
            for(LegMode mode: request.egressModes) {
                StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
                if (currentlyUnsupportedModes.contains(mode)) {
                    continue;
                }
                //TODO: add support for bike sharing
                streetRouter.streetMode = StreetMode.valueOf(mode.toString());
                streetRouter.profileRequest = request;
                // TODO add time and distance limits to routing, not just weight.
                // TODO apply walk and bike speeds and maxBike time.
                streetRouter.distanceLimitMeters =  2000; // FIXME arbitrary, and account for bike or car access mode
                if(streetRouter.setOrigin(request.toLat, request.toLon)) {
                    streetRouter.route();
                    TIntIntMap stops = streetRouter.getReachedStops();
                    egressRouter.put(mode, streetRouter);
                    LOG.info("Added {} edgres stops for mode {}",stops.size(), mode);

                } else {
                    LOG.warn("MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!", mode);
                }
            }

            option.summary = option.generateSummary();
            profileResponse.addOption(option);

            // fold access and egress times into single maps
            TIntIntMap accessTimes = combineMultimodalRoutingAccessTimes(accessRouter, stopModeAccessMap, request);
            TIntIntMap egressTimes = combineMultimodalRoutingAccessTimes(egressRouter, stopModeEgressMap, request);

            McRaptorSuboptimalPathProfileRouter router = new McRaptorSuboptimalPathProfileRouter(transportNetwork, request, accessTimes, egressTimes);
            List<PathWithTimes> usefullpathList = new ArrayList<>();

            // getPaths actually returns a set, which is important so that things are deduplicated. However we need a list
            // so we can sort it below.
            usefullpathList.addAll(router.getPaths());

            //This sort is necessary only for text debug output so it will be disabled when it is finished

            /**
             * Orders first no transfers then one transfers 2 etc
             * - then orders according to first trip:
             *   - board stop
             *   - alight stop
             *   - alight time
             * - same for one transfer trip
             */
            usefullpathList.sort((o1, o2) -> {
                int c;
                c = Integer.compare(o1.patterns.length, o2.patterns.length);
                if (c==0) {
                    c = Integer.compare(o1.boardStops[0], o2.boardStops[0]);
                }
                if (c==0) {
                    c = Integer.compare(o1.alightStops[0], o2.alightStops[0]);
                }
                if (c==0) {
                    c = Integer.compare(o1.alightTimes[0], o2.alightTimes[0]);
                }
                if (c==0 && o1.patterns.length == 2) {
                    c = Integer.compare(o1.boardStops[1], o2.boardStops[1]);
                    if (c==0) {
                        c = Integer.compare(o1.alightStops[1], o2.alightStops[1]);
                    }
                    if (c==0) {
                        c = Integer.compare(o1.alightTimes[1], o2.alightTimes[1]);
                    }
                }
                return c;
            });
            LOG.info("Usefull paths:{}", usefullpathList.size());
            int seen_paths = 0;
            int boardStop =-1, alightStop = -1;
            for (PathWithTimes path : usefullpathList) {
                profileResponse.addTransitPath(accessRouter, egressRouter, stopModeAccessMap, stopModeEgressMap, path, transportNetwork, request.getFromTimeDateZD());
                //LOG.info("Num patterns:{}", path.patterns.length);
                //ProfileOption transit_option = new ProfileOption();


                /*if (path.patterns.length == 1) {
                    continue;
                }*/

                /*if (seen_paths > 20) {
                    break;
                }*/

                if (LOG.isDebugEnabled()) {
                    LOG.debug(" ");
                    for (int i = 0; i < path.patterns.length; i++) {
                        //TransitSegment transitSegment = new TransitSegment(transportNetwork.transitLayer, path.boardStops[i], path.alightStops[i], path.patterns[i]);
                        if (!(((boardStop == path.boardStops[i] && alightStop == path.alightStops[i])))) {
                            LOG.debug("   BoardStop: {} pattern: {} allightStop: {}", path.boardStops[i],
                                path.patterns[i], path.alightStops[i]);
                        }
                        TripPattern pattern = transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]);
                        if (pattern.routeIndex >= 0) {
                            RouteInfo routeInfo = transportNetwork.transitLayer.routes.get(pattern.routeIndex);
                            LOG.debug("     Pattern:{} on route:{} ({}) with {} stops", path.patterns[i],
                                routeInfo.route_long_name, routeInfo.route_short_name, pattern.stops.length);
                        }
                        LOG.debug("     {}->{} ({}:{})", transportNetwork.transitLayer.stopNames.get(path.boardStops[i]),
                            transportNetwork.transitLayer.stopNames.get(path.alightStops[i]),
                            path.alightTimes[i] / 3600, path.alightTimes[i] % 3600 / 60);
                        //transit_option.addTransit(transitSegment);
                    }
                    boardStop = path.boardStops[0];
                    alightStop = path.alightStops[0];
                }
                seen_paths++;
            }
            profileResponse.generateStreetTransfers(transportNetwork, request);
        } else {
            option.summary = option.generateSummary();
            profileResponse.addOption(option);
        }

        LOG.info("Returned {} options", profileResponse.getOptions().size());

        return profileResponse;
    }

    /**
     * Uses 2 streetSearches to get P+R path
     *
     * First CAR search from fromLat/fromLon to all car parks. Then from those found places WALK search.
     *
     * Result is then used as access part. Since P+R in direct mode is useless.
     * @param request profileRequest from which from/to destination is used
     * @param streetRouter where profileRequest was already set
     * @return null if path isn't found
     */
    private StreetRouter findParkRidePath(ProfileRequest request, StreetRouter streetRouter) {
        streetRouter.streetMode = StreetMode.CAR;
        streetRouter.distanceLimitMeters = 15_000;
        if(streetRouter.setOrigin(request.fromLat, request.fromLon)) {
            streetRouter.route();
            TIntObjectMap<StreetRouter.State> carParks = streetRouter.getReachedVertices(VertexStore.VertexFlag.PARK_AND_RIDE);
            LOG.info("CAR PARK: Found {} car parks", carParks.size());
            StreetRouter walking = new StreetRouter(transportNetwork.streetLayer);
            walking.streetMode = StreetMode.WALK;
            walking.profileRequest = request;
            walking.distanceLimitMeters = 2_000 + streetRouter.distanceLimitMeters;
            walking.setOrigin(carParks, CAR_PARK_DROPOFF_TIME_S, CAR_PARK_DROPOFF_COST, LegMode.CAR_PARK);
            walking.route();
            walking.previous = streetRouter;
            return walking;
        } else {
            return null;
        }
    }

    /**
     * Uses 3 streetSearches to first search from fromLat/fromLon to all the bike renting places in
     * WALK mode. Then from all found bike renting places to other bike renting places with BIKE
     * and then just routing from those found bike renting places in WALK mode.
     *
     * This can then be used as streetRouter for access paths or as a direct search for specific destination
     *
     * Last streetRouter (WALK from bike rentals) is returned
     * @param request profileRequest from which from/to destination is used
     * @param streetRouter where profileRequest was already set
     * @return null if path isn't found
     */
    private StreetRouter findBikeRentalPath(ProfileRequest request, StreetRouter streetRouter) {
        streetRouter.streetMode = StreetMode.WALK;
        // TODO add time and distance limits to routing, not just weight.
        // TODO apply walk and bike speeds and maxBike time.
        streetRouter.distanceLimitMeters = 2_000;
        if(streetRouter.setOrigin(request.fromLat, request.fromLon)) {
            streetRouter.route();
            //This finds all the nearest bicycle rent stations when walking
            TIntObjectMap<StreetRouter.State> bikeStations = streetRouter.getReachedVertices(VertexStore.VertexFlag.BIKE_SHARING);
            LOG.info("BIKE RENT: Found {} bike stations in {}km walk distance", bikeStations.size(), streetRouter.distanceLimitMeters/1000);
                        /*LOG.info("Start to bike share:");
                        bikeStations.forEachEntry((idx, state) -> {
                            LOG.info("   {} ({}m)", idx, state.distance);
                            return true;
                        });*/

            //This finds best cycling path from best start bicycle station to end bicycle station
            StreetRouter bicycle = new StreetRouter(transportNetwork.streetLayer);
            bicycle.previous = streetRouter;
            bicycle.streetMode = StreetMode.BICYCLE;
            bicycle.profileRequest = request;
            bicycle.distanceLimitMeters = 15_000 + streetRouter.distanceLimitMeters;
            bicycle.setOrigin(bikeStations, BIKE_RENTAL_PICKUP_TIME_S, BIKE_RENTAL_PICKUP_COST, LegMode.BICYCLE_RENT);
            bicycle.route();
            TIntObjectMap<StreetRouter.State> cycledStations = bicycle.getReachedVertices(VertexStore.VertexFlag.BIKE_SHARING);
            LOG.info("BIKE RENT: Found {} cycled stations in {}km cycled distance", cycledStations.size(), bicycle.distanceLimitMeters/1000);
                        /*LOG.info("Bike share to bike share:");
                        cycledStations.retainEntries((idx, state) -> {
                            if (bikeStations.containsKey(idx)) {
                                LOG.warn("  MM:{} ({}m)", idx, state.distance/1000);
                                return false;
                            } else {
                                LOG.info("   {} ({}m)", idx, state.distance / 1000);
                                return true;
                            }

                        });*/
            //This searches for walking path from end bicycle station to end point
            StreetRouter end = new StreetRouter(transportNetwork.streetLayer);
            end.streetMode = StreetMode.WALK;
            end.profileRequest = request;
            end.distanceLimitMeters = 2_000 + bicycle.distanceLimitMeters;
            end.setOrigin(cycledStations, BIKE_RENTAL_DROPOFF_TIME_S, BIKE_RENTAL_DROPOFF_COST, LegMode.BICYCLE_RENT);
            end.route();
            end.previous = bicycle;
            return end;
        } else {
            return null;
        }
    }

    /** Combine the results of several street searches using different modes into a single map
     * It also saves with which mode was stop reached into stopModeMap. This map is then used
     * to create itineraries in response */
    private TIntIntMap combineMultimodalRoutingAccessTimes(Map<LegMode, StreetRouter> routers,
        TIntObjectMap<LegMode> stopModeMap, ProfileRequest request) {
        // times at transit stops
        TIntIntMap times = new TIntIntHashMap();

        // weights at transit stops
        TIntIntMap weights = new TIntIntHashMap();

        for (Map.Entry<LegMode, StreetRouter> entry : routers.entrySet()) {
            int maxTime = 30;
            int minTime = 0;
            int penalty = 0;

            LegMode mode = entry.getKey();
            switch (mode) {
                case BICYCLE:
                    maxTime = request.maxBikeTime;
                    minTime = request.minBikeTime;
                    penalty = BIKE_PENALTY;
                    break;
                case BICYCLE_RENT:
                    // TODO this is not strictly correct, bike rent is partly walking
                    maxTime = request.maxBikeTime;
                    minTime = request.minBikeTime;
                    penalty = BIKESHARE_PENALTY;
                    break;
                case WALK:
                    maxTime = request.maxWalkTime;
                    break;
                case CAR:
                    //TODO this is not strictly correct, CAR PARK is partly walking
                case CAR_PARK:
                    maxTime = request.maxCarTime;
                    minTime = request.minCarTime;
                    penalty = CAR_PENALTY;
                    break;
            }

            maxTime *= 60; // convert to seconds
            minTime *= 60; // convert to seconds

            final int maxTimeFinal = maxTime;
            final int minTimeFinal = minTime;
            final int penaltyFinal = penalty;

            StreetRouter router = entry.getValue();
            router.getReachedStops().forEachEntry((stop, time) -> {
                if (time > maxTimeFinal || time < minTimeFinal) return true;
                //Skip stops that can't be used with wheelchairs if wheelchair routing is requested
                if (request.wheelchair && !transportNetwork.transitLayer.stopsWheelchair.get(stop)) {
                    return true;
                }

                int weight = time + penaltyFinal;

                // There are penalties for using certain modes, to avoid bike/car trips that are only marginally faster
                // than walking, so we use weights to decide which mode "wins" to access a particular stop.
                if (!weights.containsKey(stop) || weight < weights.get(stop)) {
                    times.put(stop, time);
                    weights.put(stop, weight);
                    stopModeMap.put(stop, mode);
                }

                return true; // iteration should continue
            });
        }

        // we don't want to explore a boatload of access/egress stops. Pick only the closest several hundred.
        // What this means is that in urban environments you'll get on the bus nearby, in suburban environments
        // you may walk/bike/drive a very long way.
        // NB in testing it's not clear this actually does a lot for performance, maybe 1-1.5s
        int stopsFound = times.size();
        if (stopsFound > MAX_ACCESS_STOPS) {
            TIntList timeList = new TIntArrayList();
            times.forEachValue(timeList::add);

            timeList.sort();

            //This gets last time in timeList
            int cutoff = timeList.get(MAX_ACCESS_STOPS); //it needs to be same as MAX_ACCESS_STOPS since if there are minimally MAX_ACCESS_STOPS + 1 stops the indexes are from 0-MAX_ACCESS_STOPS

            for (TIntIntIterator it = times.iterator(); it.hasNext();) {
                it.advance();

                if (it.value() > cutoff) it.remove();
            }

            LOG.warn("{} stops found, using {} nearest", stopsFound, times.size());
        }

        LOG.info("{} stops found", stopsFound);

        // return the times, not the weights
        return times;
    }
}
