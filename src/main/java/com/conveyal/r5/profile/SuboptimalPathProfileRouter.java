package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.analyst.scenario.RemoveTrip;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.publish.StaticPropagatedTimesStore;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.google.common.collect.Lists;
import gnu.trove.map.TIntIntMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * A profile router that finds some number of suboptimal paths.
 */
public class SuboptimalPathProfileRouter {
    private static final Logger LOG = LoggerFactory.getLogger(SuboptimalPathProfileRouter.class);

    /**
     * how many minutes to search past the end of the time window and then discard.
     * We do this because the searches earlier in the time window have their transfers "pre-compressed" because they
     * would be dominated by trips later in the time window, but this does not hold for trips near the end of the time
     * window. See extensive discussion in https://github.com/conveyal/r5/issues/42.
     */
    public static final int OVERSEARCH_MINUTES = 30;

    public final TransportNetwork transportNetwork;
    public final ProfileRequest req;

    private Set<PathWithTimes> paths = new HashSet<>();

    private TIntIntMap stopsNearOrigin;

    private TIntIntMap stopsNearDestination;

    private Queue<TIntSet> patternsToBan = new ArrayDeque<>();

    // if an option has a min time less than this, retain it
    private int worstTimeToAccept = Integer.MAX_VALUE;

    public SuboptimalPathProfileRouter(TransportNetwork network, ProfileRequest req) {
        this.transportNetwork = network;
        this.req = req;
    }

    /** Find paths (options) that could be used */
    public Collection<PathWithTimes> route () {
        LOG.info("Performing access search");
        StreetRouter access = new StreetRouter(transportNetwork.streetLayer);
        access.setOrigin(req.fromLat, req.fromLon);

        // TODO origin search with multiple modes

        // TODO true time limit (important for driving)
        access.distanceLimitMeters = (int) (req.walkSpeed * req.maxWalkTime * 60);
        access.route();
        stopsNearOrigin = access.getReachedStops();

        LOG.info("Performing egress search");
        // FIXME for now, egress search is always by walking
        StreetRouter egress = new StreetRouter(transportNetwork.streetLayer);
        // TODO reverse search (doesn't really matter for peds)
        egress.setOrigin(req.toLat, req.toLon);
        egress.distanceLimitMeters = (int) (req.walkSpeed * req.maxWalkTime * 60);
        egress.route();
        stopsNearDestination = egress.getReachedStops();

        LOG.info("Performing transit search for optimal routes");
        Set<PathWithTimes> optimalPaths = findPaths(null);
        // no need to filter, all optimal paths are optimal at some point so they by definition overlap with the best option
        this.paths.addAll(optimalPaths);

        worstTimeToAccept = optimalPaths.stream().mapToInt(p -> p.max + req.suboptimalMinutes).min().getAsInt();

        LOG.info("Found {} optimal paths", optimalPaths.size());

        // score all the different options and ban the combinatorial possibilities.
        scoreAndAddToQueue(optimalPaths);

        LOG.info("Running {} searches for suboptimal paths", this.patternsToBan.size());

        while (!patternsToBan.isEmpty()) {
            Set<PathWithTimes> paths = findPaths(patternsToBan.remove());
            paths.stream().filter(p -> p.min < worstTimeToAccept).forEach(this.paths::add);
        }

        LOG.info("done");

        // TODO once this all works, comment out
        dump();

        return this.paths;
    }

    /** find paths and add them to the set of possible paths, respecting banned patterns */
    private Set<PathWithTimes> findPaths (TIntSet bannedPatterns) {
        Set<PathWithTimes> paths = new HashSet<>();

        // make defensive copy, eventually this may be called in parallel
        ProfileRequest req = this.req.clone();
        req.scenario = new Scenario(0);
        req.scenario.modifications = new ArrayList<>();
        if (this.req.scenario != null && this.req.scenario.modifications != null)
            req.scenario.modifications.addAll(this.req.scenario.modifications);

        // extend the time window because the end of the time window has trips with accurate times but without transfer
        // compression.
        req.toTime += OVERSEARCH_MINUTES * 60;

        // remove appropriate trips
        if (bannedPatterns != null && !bannedPatterns.isEmpty()) {
            RemoveTrip rt = new RemoveTrip();
            rt.patternIds = bannedPatterns;
            req.scenario.modifications.add(rt);
        }

        RaptorWorker worker = new RaptorWorker(transportNetwork.transitLayer, null, req);
        StaticPropagatedTimesStore pts = (StaticPropagatedTimesStore) worker.runRaptor(stopsNearOrigin, null, new TaskStatistics());

        // chop off the last few minutes of the time window so we don't get a lot of unoptimized
        // trips without transfer compression near the end of the time window. See https://github.com/conveyal/r5/issues/42
        int iterations = pts.times.length - OVERSEARCH_MINUTES;
        int stops = pts.times[0].length;

        // only find a single optimal path each minute
        PathWithTimes[] optimalPathsEachIteration = new PathWithTimes[iterations];
        int[] optimalTimesEachIteration = new int[iterations];
        Arrays.fill(optimalTimesEachIteration, Integer.MAX_VALUE);

        for (int stop = 0; stop < stops; stop++) {
            if (!stopsNearDestination.containsKey(stop)) continue;

            for (int iter = 0; iter < iterations; iter++) {
                RaptorState state = worker.statesEachIteration.get(iter);
                // only compute a path if this stop was reached
                if (state.bestNonTransferTimes[stop] != RaptorWorker.UNREACHED &&
                        state.bestNonTransferTimes[stop] + stopsNearDestination.get(stop) < optimalTimesEachIteration[iter]) {
                    optimalTimesEachIteration[iter] = state.bestNonTransferTimes[stop] + stopsNearDestination.get(stop);
                    PathWithTimes path = new PathWithTimes(state, stop, transportNetwork, req, stopsNearOrigin, stopsNearDestination);
                    optimalPathsEachIteration[iter] = path;
                }
            }
        }

        paths.addAll(Arrays.asList(optimalPathsEachIteration));

        return paths;
    }

    /** score possible routes to ban and add them to the queue */
    private void scoreAndAddToQueue (Set<PathWithTimes> paths) {
        // enqueue all combinatorial possibilities for routes to ban
        TIntSet patternsUsed = new TIntHashSet();

        paths.forEach(p -> patternsUsed.addAll(p.patterns));

        // We need a heuristic to determine what to ban, so we just ban all single routes, and all combinations of routes
        // using a particular path, and then all patterns used.
        // One concern is that we'll just find other paths we already found.

        patternsUsed.forEach(i -> {
            TIntHashSet set = new TIntHashSet(1);
            set.add(i);
            patternsToBan.add(set);
            return true;
        });

        paths.forEach(p -> patternsToBan.add(new TIntHashSet(p.patterns)));

        patternsToBan.add(patternsUsed);
    }

    public void dump () {
        LOG.info("BEGIN DUMP OF PROFILE ROUTES");
        for (PathWithTimes pwt : this.paths) {
            StringBuilder sb = new StringBuilder();
            sb.append("min/avg/max ");
            sb.append(pwt.min / 60);
            sb.append('/');
            sb.append(pwt.avg / 60);
            sb.append('/');
            sb.append(pwt.max / 60);

            sb.append(' ');

            for (int i = 0; i < pwt.length; i++) {
                int routeIdx = transportNetwork.transitLayer.tripPatterns.get(pwt.patterns[i]).routeIndex;
                RouteInfo ri = transportNetwork.transitLayer.routes.get(routeIdx);
                sb.append(ri.route_short_name != null ? ri.route_short_name : ri.route_long_name);
                sb.append(" -> ");
            }

            System.out.println(sb.toString());
        }

        LOG.info("END DUMP OF PROFILE ROUTES");
    }
}
