package com.conveyal.gtfs.api.graphql.fetchers;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.gtfs.api.graphql.WrappedGTFSEntity;
import com.conveyal.gtfs.model.FeedInfo;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Stop;
import graphql.schema.DataFetchingEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by matthewc on 3/9/16.
 */
public class StopFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(StopFetcher.class);
    private static final Double DEFAULT_RADIUS = 1.0; // default 1 km search radius

    /** top level stops query (i.e. not inside a stoptime etc) */
    public static List<WrappedGTFSEntity<Stop>> apex(DataFetchingEnvironment env) {
        Map<String, Object> args = env.getArguments();

        Collection<GTFSFeed> feeds;

        List<String> feedId = (List<String>) args.get("feed_id");
        feeds = ApiMain.getFeedSources(feedId);

        List<WrappedGTFSEntity<Stop>> stops = new ArrayList<>();

        // TODO: clear up possible scope issues feed and stop IDs
        for (GTFSFeed feed : feeds) {
            if (args.get("stop_id") != null) {
                List<String> stopId = (List<String>) args.get("stop_id");
                stopId.stream()
                        .filter(id -> id != null && feed.stops.containsKey(id))
                        .map(feed.stops::get)
                        .map(s -> new WrappedGTFSEntity(feed.uniqueId, s))
                        .forEach(stops::add);
            }
            // TODO: should pattern pre-empt route or should they operate together?
            else if (args.get("pattern_id") != null) {
                List<String> patternId = (List<String>) args.get("pattern_id");
                feed.patterns.values().stream()
                        .filter(p -> patternId.contains(p.pattern_id))
                        .map(p -> feed.getOrderedStopListForTrip(p.associatedTrips.get(0)))
                        .flatMap(List::stream)
                        .map(feed.stops::get)
                        .distinct()
                        .map(stop -> new WrappedGTFSEntity(feed.uniqueId, stop))
                        .forEach(stops::add);
            }
            else if (args.get("route_id") != null) {
                List<String> routeId = (List<String>) args.get("route_id");
                feed.patterns.values().stream()
                        .filter(p -> routeId.contains(p.route_id))
                        .map(p -> feed.getOrderedStopListForTrip(p.associatedTrips.get(0)))
                        .flatMap(List::stream)
                        .map(feed.stops::get)
                        .distinct()
                        .map(stop -> new WrappedGTFSEntity(feed.uniqueId, stop))
                        .forEach(stops::add);
            }
        }
        return stops;
    }

    public static List<WrappedGTFSEntity<Stop>> fromPattern(DataFetchingEnvironment environment) {
        WrappedGTFSEntity<Pattern> pattern = (WrappedGTFSEntity<Pattern>) environment.getSource();

        if (pattern.entity.associatedTrips.isEmpty()) {
            LOG.warn("Empty pattern!");
            return Collections.emptyList();
        }

        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(pattern.feedUniqueId);
        if (feed == null) return null;

        return feed.getOrderedStopListForTrip(pattern.entity.associatedTrips.get(0))
                .stream()
                .map(feed.stops::get)
                .map(s -> new WrappedGTFSEntity<>(feed.uniqueId, s))
                .collect(Collectors.toList());
    }

    public static Long fromPatternCount(DataFetchingEnvironment environment) {
        WrappedGTFSEntity<Pattern> pattern = (WrappedGTFSEntity<Pattern>) environment.getSource();

        if (pattern.entity.associatedTrips.isEmpty()) {
            LOG.warn("Empty pattern!");
            return 0L;
        }

        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(pattern.feedUniqueId);
        if (feed == null) return null;

        return feed.getOrderedStopListForTrip(pattern.entity.associatedTrips.get(0))
                .stream().count();
    }

    public static List<WrappedGTFSEntity<Stop>> fromFeed(DataFetchingEnvironment env) {
        WrappedGTFSEntity<FeedInfo> fi = (WrappedGTFSEntity<FeedInfo>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(fi.feedUniqueId);
        if (feed == null) return null;

        Collection<Stop> stops = feed.stops.values();
        List<String> stopIds = env.getArgument("stop_id");

        if (stopIds != null) {
            return stopIds.stream()
                    .filter(id -> id != null && feed.stops.containsKey(id))
                    .map(feed.stops::get)
                    .map(s -> new WrappedGTFSEntity<>(feed.uniqueId, s))
                    .collect(Collectors.toList());
        }
        return stops.stream()
                .map(s -> new WrappedGTFSEntity<>(feed.uniqueId, s))
                .collect(Collectors.toList());
    }

    public static Long fromFeedCount(DataFetchingEnvironment env) {
        WrappedGTFSEntity<FeedInfo> fi = (WrappedGTFSEntity<FeedInfo>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(fi.feedUniqueId);
        if (feed == null) return null;
        Collection<Stop> stops = feed.stops.values();
        return stops.stream().count();
    }
}
