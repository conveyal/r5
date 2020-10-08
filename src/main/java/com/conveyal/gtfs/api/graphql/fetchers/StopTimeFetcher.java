package com.conveyal.gtfs.api.graphql.fetchers;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.gtfs.api.graphql.WrappedGTFSEntity;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import graphql.schema.DataFetchingEnvironment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Created by matthewc on 3/9/16.
 */
public class StopTimeFetcher {
    public static List<WrappedGTFSEntity<StopTime>> apex(DataFetchingEnvironment env) {
        Collection<GTFSFeed> feeds;

        List<String> feedId = env.getArgument("feed_id");
        feeds = ApiMain.getFeedSources(feedId);

        List<WrappedGTFSEntity<StopTime>> stopTimes = new ArrayList<>();

        // TODO: clear up possible scope issues feed and stop IDs
        for (GTFSFeed feed : feeds) {
            if (env.getArgument("trip_id") != null) {
                List<String> tripId = env.getArgument("trip_id");
                tripId.stream()
                        .map(id -> feed.getOrderedStopTimesForTrip(id))
                        .map(st -> new WrappedGTFSEntity(feed.uniqueId, st))
                        .forEach(stopTimes::add);
            }
        }

        return stopTimes;
    }
    public static List<WrappedGTFSEntity<StopTime>> fromTrip(DataFetchingEnvironment env) {
        WrappedGTFSEntity<Trip> trip = (WrappedGTFSEntity<Trip>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(trip.feedUniqueId);
        if (feed == null) return null;

        List<String> stopIds = env.getArgument("stop_id");

        // get stop_times in order
        Stream<StopTime> stopTimes = StreamSupport.stream(feed.getOrderedStopTimesForTrip(trip.entity.trip_id).spliterator(), false);
        if (stopIds != null) {
            return stopTimes
                    .filter(stopTime -> stopIds.contains(stopTime.stop_id))
                    .map(st -> new WrappedGTFSEntity<>(feed.uniqueId, st))
                    .collect(Collectors.toList());
        }
        else {
            return stopTimes
                    .map(st -> new WrappedGTFSEntity<>(feed.uniqueId, st))
                    .collect(Collectors.toList());
        }
    }

}
