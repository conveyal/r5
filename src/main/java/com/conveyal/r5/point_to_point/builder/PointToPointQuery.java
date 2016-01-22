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
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.map.TIntIntMap;
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

    private static final EnumSet<LegMode> currentlyUnsupportedModes = EnumSet.of(LegMode.BICYCLE_RENT, LegMode.CAR_PARK);

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
            if (currentlyUnsupportedModes.contains(mode)) {
                continue;
            }
            //TODO: add support for bike sharing and park and ri
            streetRouter.mode = Mode.valueOf(mode.toString());
            streetRouter.profileRequest = request;
            // TODO add time and distance limits to routing, not just weight.
            // TODO apply walk and bike speeds and maxBike time.
            streetRouter.distanceLimitMeters = transit ? 200 : 100_000; // FIXME arbitrary, and account for bike or car access mode
            if(streetRouter.setOrigin(request.fromLat, request.fromLon)) {
                streetRouter.route();
                if (transit) {
                    TIntIntMap stops = streetRouter.getReachedStops();
                    reachedTransitStops.putAll(stops);
                    LOG.info("Added {} stops for mode {}",stops.size(), mode);
                    accessRouter.put(mode, streetRouter);
                    ts.initialStopSearch += (int) (System.currentTimeMillis() - initialStopStartTime);

                    //TODO: we need to save street paths from start to all the stops somehow
                }
                StreetRouter.State lastState = streetRouter.getState(split);
                if (lastState != null) {
                    StreetPath streetPath = new StreetPath(lastState, transportNetwork);
                    StreetSegment streetSegment = new StreetSegment(streetPath, mode);
                    //TODO: this needs to be different if transit is requested
                    if (transit) {
                        //addAccess
                    } else {
                        option.addDirect(streetSegment, request.getFromTimeDateZD());
                    }

                }
            } else {
                LOG.warn("MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!", mode);
            }
        }
        if (transit) {
            //For direct modes
            for(LegMode mode: modes) {
                StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
                if (currentlyUnsupportedModes.contains(mode)) {
                    continue;
                }
                //TODO: add support for bike sharing and park and ride
                streetRouter.mode = Mode.valueOf(mode.toString());
                streetRouter.profileRequest = request;
                // TODO add time and distance limits to routing, not just weight.
                // TODO apply walk and bike speeds and maxBike time.
                streetRouter.distanceLimitMeters =  100_000; // FIXME arbitrary, and account for bike or car access mode
                if(streetRouter.setOrigin(request.fromLat, request.fromLon)) {
                    streetRouter.route();
                    StreetRouter.State lastState = streetRouter.getState(split);
                    if (lastState != null) {
                        StreetPath streetPath = new StreetPath(lastState, transportNetwork);
                        StreetSegment streetSegment = new StreetSegment(streetPath, mode);
                        //This always adds direct mode
                        option.addDirect(streetSegment, request.getFromTimeDateZD());
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
                streetRouter.distanceLimitMeters =  200; // FIXME arbitrary, and account for bike or car access mode
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
            RaptorWorker worker = new RaptorWorker(transportNetwork.transitLayer, null, request);
            StaticPropagatedTimesStore pts = (StaticPropagatedTimesStore) worker.runRaptor(reachedTransitStops, null, ts);
            ts.targetsReached = pts.countTargetsReached();
            //FIXME: do we need to use all the iterations?
            int iterations = pts.times.length;
            //TODO: use only stops which are usefull to get to destination
            int stops = pts.times[0].length;
            int usefull_path = 0;
            List<Path> usefullpathList = new ArrayList<>();

            //This is copied from StaticServer
            for (int stop = 0; stop < stops; stop++) {
                int prev = 0;
                int prevPath = 0;
                int maxPathIdx = 0;

                TObjectIntMap<Path> paths = new TObjectIntHashMap<>();
                List<Path> pathList = new ArrayList<>();

                for (int iter = 0; iter < iterations; iter++) {
                    int time = pts.times[iter][stop];
                    if (time == Integer.MAX_VALUE) time = -1;

                    //out.writeInt(time - prev);
                    prev = time;

                    // write out which path to use, delta coded
                    int pathIdx = -1;

                    RaptorState state = worker.statesEachIteration.get(iter);
                    // only compute a path if this stop was reached
                    if (state.bestNonTransferTimes[stop] != RaptorWorker.UNREACHED) {
                        Path path = new Path(state, stop);
                        if (!paths.containsKey(path)) {
                            paths.put(path, maxPathIdx++);
                            pathList.add(path);
                        }

                        pathIdx = paths.get(path);
                    }

                    //out.writeInt(pathIdx - prevPath);
                    prevPath = pathIdx;
                }

                // write the paths
                //out.writeInt(pathList.size());
                //LOG.info("Paths:{}", pathList.size());
                for (Path path : pathList) {
                    /**
                     * Since search finds paths from requested stops to all of the stops we need to filter the paths
                     * This skips the paths that don't end last leg in one of destination transit stops
                     * Multiple legs here are multiple transit transfers
                     */
                    int lastStop = path.alightStops[path.patterns.length-1];
                    if (!destinationTransitStops.containsKey(lastStop)) {
                        continue;
                    }
                    usefullpathList.add(path);
                    usefull_path++;

                    /*
                    //out.writeInt(path.patterns.length);
                    //LOG.info("Num patterns:{}", path.patterns.length);
                    ProfileOption transit_option = new ProfileOption();

                    for (int i = 0; i < path.patterns.length; i++) {
                        TransitSegment transitSegment = new TransitSegment(transportNetwork.transitLayer, path.boardStops[i], path.alightStops[i], path.patterns[i]);
                        //LOG.info("   BoardStop: {} pattern: {} allightStop: {}", path.boardStops[i], path.patterns[i], path.alightStops[i]);
                        TripPattern pattern =transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]);
                        if (pattern.routeIndex >= 0) {
                            RouteInfo routeInfo = transportNetwork.transitLayer.routes.get(pattern.routeIndex);
                            //LOG.info("     Pattern:{} on route:{} ({}) with {} stops", path.patterns[i],routeInfo.route_long_name, routeInfo.route_short_name,
                            //    pattern.stops.length);
                        }
                        //LOG.info("     {}->{} ({}:{})", transportNetwork.transitLayer.stopNames.get(path.boardStops[i]),
                        //    transportNetwork.transitLayer.stopNames.get(path.alightStops[i]),
                        //    path.alightTimes[i]/3600, path.alightTimes[i]%3600/60);
                        transit_option.addTransit(transitSegment);
                    }
                    transit_option.summary = transit_option.generateSummary();
                    profileResponse.addOption(transit_option);
                    */
                }
            }
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
            LOG.info("Usefull paths:{}", usefull_path);
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


        return profileResponse;
    }
}
