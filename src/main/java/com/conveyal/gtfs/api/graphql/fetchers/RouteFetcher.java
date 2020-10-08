package com.conveyal.gtfs.api.graphql.fetchers;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.gtfs.api.graphql.WrappedGTFSEntity;
import com.conveyal.gtfs.model.FeedInfo;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import graphql.schema.DataFetchingEnvironment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by matthewc on 3/10/16.
 */
public class RouteFetcher {
    public static List<WrappedGTFSEntity<Route>> apex (DataFetchingEnvironment environment) {
        Map<String, Object> args = environment.getArguments();

        Collection<GTFSFeed> feeds;

        List<String> feedId = (List<String>) args.get("feed_id");
        feeds = ApiMain.getFeedSources(feedId);

        List<WrappedGTFSEntity<Route>> routes = new ArrayList<>();

        // TODO: clear up possible scope issues feed and route IDs
        for (GTFSFeed feed : feeds) {
            if (args.get("route_id") != null) {
                List<String> routeId = (List<String>) args.get("route_id");
                routeId.stream()
                        .filter(feed.routes::containsKey)
                        .map(feed.routes::get)
                        .map(r -> new WrappedGTFSEntity(feed.uniqueId, r))
                        .forEach(routes::add);
            }
            else {
                feed.routes.values().stream().map(r -> new WrappedGTFSEntity<>(feed.uniqueId, r)).forEach(routes::add);
            }
        }

        return routes;
    }

    public static List<WrappedGTFSEntity<Route>> fromStop(DataFetchingEnvironment environment) {
        WrappedGTFSEntity<Stop> stop = (WrappedGTFSEntity<Stop>) environment.getSource();
        List<String> routeIds = environment.getArgument("route_id");

        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(stop.feedUniqueId);
        if (feed == null) return null;

        List<WrappedGTFSEntity<Route>> routes = feed.patterns.values().stream()
                .filter(p -> p.orderedStops.contains(stop.entity.stop_id))
                .map(p -> feed.routes.get(p.route_id))
                .distinct()
                .map(r -> new WrappedGTFSEntity<>(feed.uniqueId, r))
                .collect(Collectors.toList());

        if (routeIds != null) {
            return routes.stream()
                    .filter(r -> routeIds.contains(r.entity.route_id))
                    .collect(Collectors.toList());
        }
        else {
            return routes;
        }
    }

    public static WrappedGTFSEntity<Route> fromPattern(DataFetchingEnvironment env) {
        WrappedGTFSEntity<Pattern> pattern = (WrappedGTFSEntity<Pattern>) env.getSource();
        List<String> routeIds = env.getArgument("route_id");

        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(pattern.feedUniqueId);
        if (feed == null) return null;

        return new WrappedGTFSEntity<>(feed.uniqueId, feed.routes.get(pattern.entity.route_id));
    }

    public static List<WrappedGTFSEntity<Route>> fromFeed(DataFetchingEnvironment environment) {
        WrappedGTFSEntity<FeedInfo> fi = (WrappedGTFSEntity<FeedInfo>) environment.getSource();
        List<String> routeIds = environment.getArgument("route_id");

        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(fi.feedUniqueId);
        if (feed == null) return null;

        if (routeIds != null) {
            return routeIds.stream()
                    .filter(id -> id != null && feed.routes.containsKey(id))
                    .map(feed.routes::get)
                    .map(r -> new WrappedGTFSEntity<>(feed.uniqueId, r))
                    .collect(Collectors.toList());
        }
        else {
            return feed.routes.values().stream()
                    .map(r -> new WrappedGTFSEntity<>(feed.uniqueId, r))
                    .collect(Collectors.toList());
        }
    }

    public static Long fromFeedCount(DataFetchingEnvironment environment) {
        WrappedGTFSEntity<FeedInfo> fi = (WrappedGTFSEntity<FeedInfo>) environment.getSource();

        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(fi.feedUniqueId);
        if (feed == null) return null;

        return feed.routes.values().stream().count();
    }
}
