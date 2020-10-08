package com.conveyal.gtfs.api.graphql.fetchers;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.gtfs.api.graphql.WrappedGTFSEntity;
import com.conveyal.gtfs.model.FeedInfo;
import graphql.schema.DataFetchingEnvironment;
import org.locationtech.jts.geom.Geometry;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by matthewc on 3/10/16.
 */
public class FeedFetcher {
    public static List<WrappedGTFSEntity<FeedInfo>> apex(DataFetchingEnvironment environment) {
        List<String> feedId = environment.getArgument("feed_id");
        return ApiMain.getFeedSources(feedId).stream()
                .map(fs -> getFeedInfo(fs))
                .collect(Collectors.toList());

    }

    private static WrappedGTFSEntity<FeedInfo> getFeedInfo(GTFSFeed feed) {
        FeedInfo ret;
        if (feed.feedInfo.size() > 0) {
            ret = feed.feedInfo.values().iterator().next();
        } else {
            ret = new FeedInfo();
        }
        // NONE is a special value used in GTFS Lib feed info
        if (ret.feed_id == null || "NONE".equals(ret.feed_id)) {
            ret = ret.clone();
            ret.feed_id = feed.feedId;
        }
        return new WrappedGTFSEntity<>(feed.uniqueId, ret);
    }

    public static Geometry getMergedBuffer(DataFetchingEnvironment env) {
        WrappedGTFSEntity<FeedInfo> feedInfo = (WrappedGTFSEntity<FeedInfo>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(feedInfo.feedUniqueId);
        if (feed == null) return null;
        return feed.getMergedBuffers();
    }

    public static WrappedGTFSEntity<FeedInfo> forWrappedGtfsEntity (DataFetchingEnvironment env) {
        WrappedGTFSEntity<FeedInfo> feedInfo = (WrappedGTFSEntity<FeedInfo>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(feedInfo.feedUniqueId);
        if (feed == null) return null;
        return getFeedInfo(feed);
    }
}
