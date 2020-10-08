package com.conveyal.analysis.models;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.gtfs.GTFSFeed;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Represents a transport Bundle (GTFS and OSM).
 *
 * Previously the OSM was specified at the Region level, so every Bundle in the same Region used the same OSM data.
 *
 * All of the data is stored in S3, however some information is duplicated here for convenience.
 */
public class Bundle extends Model implements Cloneable {
    public String regionId;

    // Unique key that allows for linking new bundles to previously uploaded OSM.
    public String osmId;

    // Unique key that allows for linking new bundles to previously uploaded GTFS.
    public String feedGroupId;

    public double north;
    public double south;
    public double east;
    public double west;

    public LocalDate serviceStart;
    public LocalDate serviceEnd;

    public List<FeedSummary> feeds = new ArrayList<>();

    public Status status = Status.PROCESSING_OSM;
    public String statusText;

    public int feedsComplete;
    public int totalFeeds;

    public static String bundleScopeFeedId (String feedId, String feedGroupId) {
        return String.format("%s_%s", feedId, feedGroupId);
    }

    public Bundle clone () {
        try {
            return (Bundle) super.clone();
        } catch (CloneNotSupportedException e) {
            throw AnalysisServerException.unknown(e);
        }
    }

    public static class FeedSummary implements Cloneable {
        public String feedId;
        public String name;
        public String originalFileName;
        public String fileName;

        /** The feed ID scoped with the bundle ID, for use as a unique identifier on S3 and in the GTFS API */
        public String bundleScopedFeedId;

        public LocalDate serviceStart;
        public LocalDate serviceEnd;
        public long checksum;

        public FeedSummary(GTFSFeed feed, String feedGroupId) {
            feedId = feed.feedId;
            bundleScopedFeedId = Bundle.bundleScopeFeedId(feed.feedId, feedGroupId);
            name = feed.agency.size() > 0 ? feed.agency.values().iterator().next().agency_name : feed.feedId;
            checksum = feed.checksum;

            setServiceDates(feed);
        }

        /**
         * Set service start and end from the dates of service values returned from GTFSFeed. This is calculated from
         * the trip data in the feed. `feed_info.txt` is optional and many GTFS feeds do not include the fields
         * feed_start_date or feed_end_date. Therefore we instead derive the start and end dates from the stop_times
         * and trips. Also, at this time the only usage of these fields is to explicitly show in the user interface that
         * a date does or does not have service.
         */
        @JsonIgnore
        public void setServiceDates (GTFSFeed feed) {
            List<LocalDate> datesOfService = feed.getDatesOfService();
            datesOfService.sort(Comparator.naturalOrder());
            serviceStart = datesOfService.get(0);
            serviceEnd = datesOfService.get(datesOfService.size() - 1);
        }

        /**
         * Default empty constructor needed for JSON mapping
         */
        public FeedSummary () { }

        public FeedSummary clone () {
            try {
                return (FeedSummary) super.clone();
            } catch (CloneNotSupportedException e) {
                throw AnalysisServerException.unknown(e);
            }
        }
    }

    public enum Status {
        PROCESSING_OSM,
        PROCESSING_GTFS,
        DONE,
        ERROR
    }
}
