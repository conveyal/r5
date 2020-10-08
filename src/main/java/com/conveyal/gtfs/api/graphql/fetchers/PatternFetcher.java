package com.conveyal.gtfs.api.graphql.fetchers;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.gtfs.api.graphql.WrappedGTFSEntity;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Trip;
import graphql.schema.DataFetchingEnvironment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * Created by matthewc on 3/9/16.
 */
public class PatternFetcher {
    private static final Double DEFAULT_RADIUS = 1.0; // default 1 km search radius

    public static List<WrappedGTFSEntity<Pattern>> apex(DataFetchingEnvironment env) {
        Collection<GTFSFeed> feeds;

        List<String> feedId = env.getArgument("feed_id");
        feeds = ApiMain.getFeedSources(feedId);
        Map<String, Object> args = env.getArguments();
        List<WrappedGTFSEntity<Pattern>> patterns = new ArrayList<>();

        for (GTFSFeed feed : feeds) {
            if (env.getArgument("pattern_id") != null) {
                List<String> patternId = env.getArgument("pattern_id");
                patternId.stream()
                        .filter(feed.patterns::containsKey)
                        .map(feed.patterns::get)
                        .map(pattern -> new WrappedGTFSEntity(feed.uniqueId, pattern))
                        .forEach(patterns::add);
            }
            else if (env.getArgument("route_id") != null) {
                List<String> routeId = (List<String>) env.getArgument("route_id");
                feed.patterns.values().stream()
                        .filter(p -> routeId.contains(p.route_id))
                        .map(pattern -> new WrappedGTFSEntity(feed.uniqueId, pattern))
                        .forEach(patterns::add);
            }
        }

        return patterns;
    }
    public static List<WrappedGTFSEntity<Pattern>> fromRoute(DataFetchingEnvironment env) {
        WrappedGTFSEntity<Route> route = (WrappedGTFSEntity<Route>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(route.feedUniqueId);
        if (feed == null) return null;

        List<String> stopIds = env.getArgument("stop_id");
        List<String> patternId = env.getArgument("pattern_id");
        Long limit = env.getArgument("limit");

        List<WrappedGTFSEntity<Pattern>> patterns = feed.patterns.values().stream()
                .filter(p -> p.route_id.equals(route.entity.route_id))
                .map(p -> new WrappedGTFSEntity<>(feed.uniqueId, p))
                .collect(Collectors.toList());
        if (patternId != null) {
            patterns.stream()
                    .filter(p -> patternId.contains(p.entity.pattern_id))
                    .collect(Collectors.toList());
        }
        if (stopIds != null) {
            patterns.stream()
                    .filter(p -> !Collections.disjoint(p.entity.orderedStops, stopIds)) // disjoint returns true if no elements in common
                    .collect(Collectors.toList());
        }
        if (limit != null) {
            return patterns.stream().limit(limit).collect(Collectors.toList());
        }
        return patterns;
    }

    public static Long fromRouteCount(DataFetchingEnvironment env) {
        WrappedGTFSEntity<Route> route = (WrappedGTFSEntity<Route>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(route.feedUniqueId);
        if (feed == null) return null;

        return feed.patterns.values().stream()
                .filter(p -> p.route_id.equals(route.entity.route_id))
                .count();
    }

    public static WrappedGTFSEntity<Pattern> fromTrip(DataFetchingEnvironment env) {
        WrappedGTFSEntity<Trip> trip = (WrappedGTFSEntity<Trip>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(trip.feedUniqueId);
        if (feed == null) return null;

        Pattern patt = feed.patterns.get(feed.patternForTrip.get(trip.entity.trip_id));
        return new WrappedGTFSEntity<>(feed.uniqueId, patt);
    }
}
