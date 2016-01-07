package com.conveyal.r5.point_to_point.builder;

import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.api.ProfileResponse;
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

        EnumSet<Mode> modes = transit ? request.accessModes : request.directModes;
        ProfileOption option = new ProfileOption();

        Map<Integer, StreetPath> pathsToStops = new HashMap<>();

        //Routes all direct (if no transit)/access modes
        for(Mode mode: modes) {
            long initialStopStartTime = System.currentTimeMillis();
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
            streetRouter.mode = mode;
            streetRouter.profileRequest = request;
            // TODO add time and distance limits to routing, not just weight.
            // TODO apply walk and bike speeds and maxBike time.
            streetRouter.distanceLimitMeters = transit ? 2000 : 100_000; // FIXME arbitrary, and account for bike or car access mode
            if(streetRouter.setOrigin(request.fromLat, request.fromLon)) {
                streetRouter.route();
                if (transit) {
                    TIntIntMap stops = streetRouter.getReachedStops();
                    reachedTransitStops.putAll(stops);
                    LOG.info("Added {} stops for mode {}",stops.size(), mode);
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
            for(Mode mode: modes) {
                StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
                streetRouter.mode = mode;
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
                for (Path path : pathList) {
                    //out.writeInt(path.patterns.length);
                    LOG.info("Num patterns:{}", path.patterns.length);
                    ProfileOption transit_option = new ProfileOption();

                    for (int i = 0; i < path.patterns.length; i++) {
                        TransitSegment transitSegment = new TransitSegment(transportNetwork.transitLayer, path.boardStops[i], path.alightStops[i], path.patterns[i]);
                        LOG.info("   BoardStop: {} pattern: {} allightStop: {}", path.boardStops[i], path.patterns[i], path.alightStops[i]);
                        TripPattern pattern =transportNetwork.transitLayer.tripPatterns.get(path.patterns[i]);
                        if (pattern.routeIndex >= 0) {
                            RouteInfo routeInfo = transportNetwork.transitLayer.routes.get(pattern.routeIndex);
                            LOG.info("     Pattern:{} on route:{} ({}) with {} stops", path.patterns[i],routeInfo.route_long_name, routeInfo.route_short_name,
                                pattern.stops.length);
                        }
                        LOG.info("     {}->{} ({}:{})", transportNetwork.transitLayer.stopNames.get(path.boardStops[i]),
                            transportNetwork.transitLayer.stopNames.get(path.alightStops[i]),
                            path.alightTimes[i]/3600, path.alightTimes[i]%3600/60);
                        transit_option.addTransit(transitSegment);
                    }
                    transit_option.summary = transit_option.generateSummary();
                    profileResponse.addOption(transit_option);
                }
            }
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
