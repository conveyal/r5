package com.conveyal.gtfs.api;

import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GTFSFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by landon on 2/3/16.
 * TODO convert ApiMain into a Component once it's very simple.
 */
public class ApiMain {

    private static GTFSCache cache;

    public static final Logger LOG = LoggerFactory.getLogger(ApiMain.class);

    public static void initialize (GTFSCache cache) {
        ApiMain.cache = cache;
    }

    // TODO rename methods, we no longer have FeedSource.
    private static GTFSFeed getFeedSource (String uniqueId) {
        GTFSFeed feed = cache.get(uniqueId);
        // The feedId of the GTFSFeed objects may not be unique - we can have multiple versions of the same feed
        // covering different time periods, uploaded by different users. Therefore we record another ID here that is
        // known to be unique across the whole application - the ID used to fetch the feed.
        // TODO setting this field could be pushed down into cache.get() or even into the CacheLoader, but I'm doing
        //  it here to keep this a pure refactor for now.
        feed.uniqueId = uniqueId;
        return feed;
    }

    /**
     * Convenience function to get a feed source without throwing checked exceptions, for example for use in lambdas.
     * @return the GTFSFeed for the given ID, or null if an exception occurs.
     */
    public static GTFSFeed getFeedSourceWithoutExceptions (String id) {
      try {
        return getFeedSource(id);
      } catch (Exception e) {
        LOG.error("Error retrieving from cache feed " + id, e);
        return null;
      }
    }

    // TODO verify that this is not used to fetch so many feeds that it will cause some of them to be closed by eviction
    // TODO introduce checks on quantity of feeds, against max cache size, and fail hard if too many are requested.
    public static List<GTFSFeed> getFeedSources (List<String> feedIds) {
        return feedIds.stream()
                .map(ApiMain::getFeedSourceWithoutExceptions)
                .filter(fs -> fs != null)
                .collect(Collectors.toList());
    }
}
