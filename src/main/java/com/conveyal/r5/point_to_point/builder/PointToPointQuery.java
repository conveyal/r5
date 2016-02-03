package com.conveyal.r5.point_to_point.builder;

import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.ProfileOption;
import com.conveyal.r5.api.util.StreetSegment;
import com.conveyal.r5.api.util.TransitSegment;
import com.conveyal.r5.profile.*;
import com.conveyal.r5.publish.StaticPropagatedTimesStore;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Class which will make point to point or profile queries on Transport network based on profileRequest
 * Created by mabu on 23.12.2015.
 */
public class PointToPointQuery {
    private static final Logger LOG = LoggerFactory.getLogger(PointToPointQuery.class);
    public static final int RADIUS_METERS = 200;
    private final TransportNetwork transportNetwork;

    private static final EnumSet<LegMode> currentlyUnsupportedModes = EnumSet.of(LegMode.CAR_PARK);

    /** Time to rent a bike */
    private static final int BIKE_RENTAL_PICKUP_TIMEMS = 60*1000;
    /**
     * Cost of renting a bike. The cost is a bit more than actual time to model the associated cost and trouble.
     */
    private static final int BIKE_RENTAL_PICKUP_COST = 120;
    /** Time to drop-off a rented bike */
    private static final int BIKE_RENTAL_DROPOFF_TIMEMS = 30*1000;
    /** Cost of dropping-off a rented bike */
    private static final int BIKE_RENTAL_DROPOFF_COST = 30;
    /** Time to park car in P+R **/
    private static final int CAR_PARK_DROPOFF_TIMEMS = 120*1000;

    private static final int CAR_PARK_DROPOFF_COST = 120;

    public PointToPointQuery(TransportNetwork transportNetwork) {
        this.transportNetwork = transportNetwork;
    }

    //Does point to point routing with data from request
    public ProfileResponse getPlan(ProfileRequest request) {
        request.zoneId = transportNetwork.getTimeZone();
        //Do the query and return result
        ProfileResponse profileResponse = new ProfileResponse();

        //Split for end coordinate
        Split split = transportNetwork.streetLayer.findSplit(request.toLat, request.toLon,
            RADIUS_METERS);
        if (split == null) {
            throw new RuntimeException("Edge near the end coordinate wasn't found. Routing didn't start!");
        }

        boolean transit = request.useTransit();

        TaskStatistics ts = new TaskStatistics();

        TIntIntMap reachedTransitStops = new TIntIntHashMap();

        TIntIntMap destinationTransitStops = new TIntIntHashMap();

        EnumSet<LegMode> modes = transit ? request.accessModes : request.directModes;
        ProfileOption option = new ProfileOption();

        Map<LegMode, StreetRouter> accessRouter = new HashMap<>(modes.size());
        Map<LegMode, StreetRouter> egressRouter = new HashMap<>(request.egressModes.size());

        //Routes all direct (if no transit)/access modes
        for(LegMode mode: modes) {
            long initialStopStartTime = System.currentTimeMillis();
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
            if (mode == LegMode.CAR_PARK && !transit) {
                LOG.warn("Can't search for P+R without transit");
                continue;
            }
            if (mode == LegMode.CAR_PARK) {
                streetRouter.mode = Mode.CAR;
            } else {
                //TODO: add support for bike sharing and park and ride
                streetRouter.mode = Mode.valueOf(mode.toString());
            }
            streetRouter.profileRequest = request;
            // TODO add time and distance limits to routing, not just weight.
            // TODO apply walk and bike speeds and maxBike time.
            streetRouter.distanceLimitMeters = transit ? 2000 : 100_000; // FIXME arbitrary, and account for bike or car access mode
            if (mode == LegMode.CAR_PARK) {
                streetRouter.distanceLimitMeters = 15_000;
            }
            if(streetRouter.setOrigin(request.fromLat, request.fromLon)) {
                streetRouter.route();
                if (mode == LegMode.CAR_PARK) {
                    TIntObjectMap<StreetRouter.State> carParks = streetRouter.getReachedVertices(
                        VertexStore.VertexFlag.PARK_AND_RIDE);
                    LOG.info("CAR PARK: Found {} car parks", carParks.size());
                    StreetRouter walking = new StreetRouter(transportNetwork.streetLayer);
                    walking.mode = Mode.WALK;
                    walking.profileRequest = request;
                    walking.distanceLimitMeters = 2_000+streetRouter.distanceLimitMeters;
                    walking.setOrigin(carParks, CAR_PARK_DROPOFF_TIMEMS, CAR_PARK_DROPOFF_COST);
                    walking.route();
                    walking.previous = streetRouter;
                    accessRouter.put(LegMode.CAR_PARK, walking);
                    ts.initialStopSearch += (int) (System.currentTimeMillis() - initialStopStartTime);

                } else {
                    if (transit) {
                        //TIntIntMap stops = streetRouter.getReachedStops();
                        //reachedTransitStops.putAll(stops);
                        //LOG.info("Added {} stops for mode {}",stops.size(), mode);
                        accessRouter.put(mode, streetRouter);
                        ts.initialStopSearch += (int) (System.currentTimeMillis() - initialStopStartTime);

                        //TODO: we need to save street paths from start to all the stops somehow
                    }
                    StreetRouter.State lastState = streetRouter.getState(split);
                    if (lastState != null) {
                        StreetPath streetPath = new StreetPath(lastState, transportNetwork);
                        StreetSegment streetSegment = new StreetSegment(streetPath, mode,
                            transportNetwork.streetLayer);
                        //TODO: this needs to be different if transit is requested
                        if (transit) {
                            //addAccess
                        } else {
                            option.addDirect(streetSegment, request.getFromTimeDateZD());
                        }

                    }
                }
            } else {
                LOG.warn("MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!", mode);
            }
        }
        if (transit) {
            //For direct modes
            for(LegMode mode: request.directModes) {
                StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
                if (currentlyUnsupportedModes.contains(mode)) {
                    continue;
                }
                if (mode == LegMode.BICYCLE_RENT) {
                    if (!transportNetwork.streetLayer.bikeSharing) {
                        LOG.warn("Bike sharing trip requested but no bike sharing stations in the streetlayer");
                        continue;
                    }
                    streetRouter.mode = Mode.WALK;
                } else {
                    //TODO: add support for bike sharing and park and ride
                    streetRouter.mode = Mode.valueOf(mode.toString());
                }
                streetRouter.profileRequest = request;
                // TODO add time and distance limits to routing, not just weight.
                // TODO apply walk and bike speeds and maxBike time.
                if (mode == LegMode.BICYCLE_RENT) {
                    streetRouter.distanceLimitMeters = 2_000;
                } else {
                    streetRouter.distanceLimitMeters = 100_000; // FIXME arbitrary, and account for bike or car access mode
                }
                if(streetRouter.setOrigin(request.fromLat, request.fromLon)) {
                    streetRouter.route();
                    if (mode == LegMode.BICYCLE_RENT) {
                        //This finds all the nearest bicycle rent stations when walking
                        TIntObjectMap<StreetRouter.State> bikeStations = streetRouter.getReachedVertices(VertexStore.VertexFlag.BIKE_SHARING);
                        LOG.info("BIKE RENT: Found {} bike stations", bikeStations.size());
                        /*LOG.info("Start to bike share:");
                        bikeStations.forEachEntry((idx, weight) -> {
                            LOG.info("   {} ({})", idx, weight);
                            return true;
                        });*/

                        //This finds best cycling path from best start bicycle station to end bicycle station
                        StreetRouter bicycle = new StreetRouter(transportNetwork.streetLayer);
                        bicycle.mode = Mode.BICYCLE;
                        bicycle.profileRequest = request;
                        bicycle.distanceLimitMeters = 100_000;
                        bicycle.setOrigin(bikeStations, BIKE_RENTAL_PICKUP_TIMEMS, BIKE_RENTAL_PICKUP_COST);
                        bicycle.route();
                        TIntObjectMap<StreetRouter.State> cycledStations = bicycle.getReachedVertices(VertexStore.VertexFlag.BIKE_SHARING);
                        LOG.info("BIKE RENT: Found {} cycled stations", cycledStations.size());
                        /*LOG.info("Bike share to bike share:");
                        cycledStations.forEachEntry((idx, weight) -> {
                            LOG.info("   {} ({})", idx, weight);
                            return true;
                        });*/
                        //This searches for walking path from end bicycle station to end point
                        StreetRouter end = new StreetRouter(transportNetwork.streetLayer);
                        end.mode = Mode.WALK;
                        end.profileRequest = request;
                        end.distanceLimitMeters = 2_000+100_000;
                        end.setOrigin(cycledStations, BIKE_RENTAL_DROPOFF_TIMEMS, BIKE_RENTAL_DROPOFF_COST);
                        end.route();
                        StreetRouter.State lastState = end.getState(split);
                        //TODO: split geometry on different modes?
                        if (lastState != null) {
                            StreetPath streetPath = new StreetPath(lastState, transportNetwork);
                            //LOG.info("{} - {}", streetPath.getStates().getFirst().getInstant(), streetPath.getStates().getLast().getInstant());
                            //StreetSegment streetSegment = new StreetSegment(streetPath, LegMode.WALK);
                            //option.addDirect(streetSegment, ZonedDateTime.ofInstant(streetPath.getStates().getFirst().getInstant(), transportNetwork.getTimeZone()));
                            StreetRouter.State endCycling = streetPath.getStates().getFirst();
                            lastState = bicycle.getState(endCycling.vertex);
                            if (lastState != null) {
                                //Copies bikeshare setting
                                lastState.isBikeShare = endCycling.isBikeShare;
                                streetPath.add(lastState);
                                //LOG.info("  {} - {}", streetPath.getStates().getFirst().getInstant(), lastState.getInstant());
                                //streetSegment = new StreetSegment(new StreetPath(lastState, transportNetwork), LegMode.BICYCLE);
                                //option.addDirect(streetSegment, ZonedDateTime.ofInstant(streetPath.getStates().getFirst().getInstant(), transportNetwork.getTimeZone()));
                                StreetRouter.State startCycling = streetPath.getStates().getFirst();
                                lastState = streetRouter.getState(startCycling.vertex);
                                if (lastState != null) {
                                    lastState.isBikeShare = startCycling.isBikeShare;
                                    streetPath.add(lastState);
                                    //LOG.info("    {} - {}", streetPath.getStates().getFirst().getInstant(), lastState.getInstant());
                                    //streetSegment = new StreetSegment(new StreetPath(lastState, transportNetwork), LegMode.WALK);
                                    //option.addDirect(streetSegment, ZonedDateTime.ofInstant(streetPath.getStates().getFirst().getInstant(), transportNetwork.getTimeZone()));
                                    StreetSegment streetSegment = new StreetSegment(streetPath, mode,
                                        transportNetwork.streetLayer);
                                    option.addDirect(streetSegment, request.getFromTimeDateZD());
                                } else {
                                    LOG.warn("Start to cycle path missing");
                                }
                            } else {
                                LOG.warn("Cycle to cycle path not found");
                            }
                        } else {
                            LOG.warn("Not found path from cycle to end");
                        }
                    } else {
                        StreetRouter.State lastState = streetRouter.getState(split);
                        if (lastState != null) {
                            StreetPath streetPath = new StreetPath(lastState, transportNetwork);
                            StreetSegment streetSegment = new StreetSegment(streetPath, mode, transportNetwork.streetLayer);
                            //This always adds direct mode
                            option.addDirect(streetSegment, request.getFromTimeDateZD());
                        } else {
                            LOG.warn("Direct mode last state wasn't found!");
                        }
                    }
                } else {
                    LOG.warn("MODE:{}, Edge near the destination coordinate wasn't found. Routing didn't start!", mode);
                }
            }

            //For egress
            //TODO: this must be reverse search
            for(LegMode mode: request.egressModes) {
                StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
                if (currentlyUnsupportedModes.contains(mode)) {
                    continue;
                }
                //TODO: add support for bike sharing and park and ride
                streetRouter.mode = Mode.valueOf(mode.toString());
                streetRouter.profileRequest = request;
                // TODO add time and distance limits to routing, not just weight.
                // TODO apply walk and bike speeds and maxBike time.
                streetRouter.distanceLimitMeters =  2000; // FIXME arbitrary, and account for bike or car access mode
                if(streetRouter.setOrigin(request.toLat, request.toLon)) {
                    streetRouter.route();
                    TIntIntMap stops = streetRouter.getReachedStops();
                    destinationTransitStops.putAll(stops);
                    egressRouter.put(mode, streetRouter);
                    LOG.info("Added {} edgres stops for mode {}",stops.size(), mode);

                } else {
                    LOG.warn("MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!", mode);
                }
            }

            option.summary = option.generateSummary();
            profileResponse.addOption(option);

            // fold access and egress times into single maps
            TIntIntMap accessTimes = combineMultimodalRoutingAccessTimes(accessRouter, request);
            TIntIntMap egressTimes = combineMultimodalRoutingAccessTimes(egressRouter, request);

            McRaptorSuboptimalPathProfileRouter router = new McRaptorSuboptimalPathProfileRouter(transportNetwork, request, accessTimes, egressTimes);
            List<PathWithTimes> usefullpathList = new ArrayList<>();

            // getPaths actually returns a set, which is important so that things are deduplicated. However we need a list
            // so we can sort it below.
            usefullpathList.addAll(router.getPaths());

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
            for (Path path : usefullpathList) {
                profileResponse.addTransitPath(accessRouter, egressRouter, path, transportNetwork, request.getFromTimeDateZD());
                //LOG.info("Num patterns:{}", path.patterns.length);
                //ProfileOption transit_option = new ProfileOption();


                /*if (path.patterns.length == 1) {
                    continue;
                }*/

                if (seen_paths > 20) {
                    break;
                }
                LOG.info(" ");
                for (int i = 0; i < path.patterns.length; i++) {
                    //TransitSegment transitSegment = new TransitSegment(transportNetwork.transitLayer, path.boardStops[i], path.alightStops[i], path.patterns[i]);
                    if (!(((boardStop == path.boardStops[i] && alightStop == path.alightStops[i]) ))) {
                        LOG.info("   BoardStop: {} pattern: {} allightStop: {}", path.boardStops[i],
                            path.patterns[i], path.alightStops[i]);
                    }
                    TripPattern pattern =transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]);
                    if (pattern.routeIndex >= 0) {
                        RouteInfo routeInfo = transportNetwork.transitLayer.routes.get(pattern.routeIndex);
                        LOG.info("     Pattern:{} on route:{} ({}) with {} stops", path.patterns[i],routeInfo.route_long_name, routeInfo.route_short_name,
                            pattern.stops.length);
                    }
                    LOG.info("     {}->{} ({}:{})", transportNetwork.transitLayer.stopNames.get(path.boardStops[i]),
                        transportNetwork.transitLayer.stopNames.get(path.alightStops[i]),
                        path.alightTimes[i]/3600, path.alightTimes[i]%3600/60);
                    //transit_option.addTransit(transitSegment);
                }
                boardStop = path.boardStops[0];
                alightStop = path.alightStops[0];
                seen_paths++;
            }
            profileResponse.generateStreetTransfers(transportNetwork, request);
        } else {
            option.summary = option.generateSummary();
            profileResponse.addOption(option);
        }
        /**
         * TODO: search for transit from all stops accesed in stop trees in access search.
         * add them to options and generate itinerary for each time option
         * add egress part
         */

        LOG.info("Returned {} options", profileResponse.getOptions().size());

        return profileResponse;
    }

    /** Combine the results of several street searches using different modes into a single map */
    private TIntIntMap combineMultimodalRoutingAccessTimes(Map<LegMode, StreetRouter> routers, ProfileRequest request) {
        TIntIntMap ret = new TIntIntHashMap();

        for (Map.Entry<LegMode, StreetRouter> entry : routers.entrySet()) {
            int maxTime = 30;
            int minTime = 0;
            LegMode mode = entry.getKey();
            switch (mode) {
                case BICYCLE:
                // TODO this is not strictly correct, bike rent is partly walking
                case BICYCLE_RENT:
                    maxTime = request.maxBikeTime;
                    minTime = request.minBikeTime;
                    break;
                case WALK:
                    maxTime = request.maxWalkTime;
                    break;
                case CAR:
                    //TODO this is not strictly correct, CAR PARK is partly walking
                case CAR_PARK:
                    maxTime = request.maxCarTime;
                    minTime = request.minCarTime;
                    break;
            }

            maxTime *= 60; // convert to seconds
            minTime *= 60; // convert to seconds

            final int maxTimeFinal = maxTime;
            final int minTimeFinal = minTime;

            StreetRouter router = entry.getValue();
            router.getReachedStops().forEachEntry((stop, time) -> {
                if (time > maxTimeFinal || time < minTimeFinal) return true;

                if (!ret.containsKey(stop) || ret.get(stop) > time) {
                    ret.put(stop, time);
                }
                return true; // iteration should continue
            });
        }

        return ret;
    }
}
