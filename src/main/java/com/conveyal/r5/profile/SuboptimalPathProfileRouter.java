package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.analyst.scenario.RemoveTrips;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.publish.StaticPropagatedTimesStore;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Stream;

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

    public final int TIMEOUT_MS = 5000;

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

        TIntSet patternsUsed = new TIntHashSet();

        paths.forEach(p -> patternsUsed.addAll(p.patterns));

        patternsUsed.forEach(i -> {
            TIntHashSet set = new TIntHashSet(1);
            set.add(i);
            patternsToBan.add(set);
            return true;
        });

        paths.forEach(p -> patternsToBan.add(new TIntHashSet(p.patterns)));

        LOG.info("Running up to {} searches for suboptimal paths", this.patternsToBan.size());

        long startTime = System.currentTimeMillis();

        while (!patternsToBan.isEmpty() && !disjointOptionsPresent() && System.currentTimeMillis() < startTime + TIMEOUT_MS) {
            Set<PathWithTimes> paths = findPaths(patternsToBan.remove());
            paths.stream().filter(p -> p.min < worstTimeToAccept).forEach(this.paths::add);
        }

        if (!disjointOptionsPresent()) LOG.warn("Found no disjoint options!");

        LOG.info("done");

        // TODO once this all works, comment out
        dump();

        return this.paths;
    }

    /** are there any disjoint options (options which do not share patterns)? */
    private boolean disjointOptionsPresent () {
        // count how many times each pattern is used
        TIntIntMap routeUsage = new TIntIntHashMap();
        for (PathWithTimes path : paths) {
            for (int pattern : path.patterns) {
                routeUsage.adjustOrPutValue(transportNetwork.transitLayer.tripPatterns.get(pattern).routeIndex, 1, 1);
            }
        }

        // check if any path is disjoint
        PATHS: for (PathWithTimes path : paths) {
            for (int pattern : path.patterns) {
                if (routeUsage.get(transportNetwork.transitLayer.tripPatterns.get(pattern).routeIndex) > 1) continue PATHS; // not disjoint
            }

            // this path is disjoint
            return true;
        }

        return false;
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
            RemoveTrips rt = new RemoveTrips();
            // FIXME how do patterns have IDs, and why is this being done with Modifications not a simple set?
            rt.patternIds = bannedPatterns;
            req.scenario.modifications.add(rt);
        }

        TransportNetwork modified = req.scenario.applyToTransportNetwork(transportNetwork);

        RaptorWorker worker = new RaptorWorker(modified.transitLayer, null, req);
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
                    PathWithTimes path = new PathWithTimes(state, stop, modified, req, stopsNearOrigin, stopsNearDestination);
                    optimalPathsEachIteration[iter] = path;
                }
            }
        }

        // map pattern ids back to the original transport network
        Stream.of(optimalPathsEachIteration).forEach(path -> {
            for (int pidx = 0; pidx < path.length; pidx++) {
                path.patterns[pidx] = modified.transitLayer.tripPatterns.get(path.patterns[pidx]).originalId;
            }
        });

        Stream.of(optimalPathsEachIteration).filter(p -> p != null).forEach(paths::add);

        return paths;
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
