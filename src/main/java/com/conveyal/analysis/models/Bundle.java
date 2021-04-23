package com.conveyal.analysis.models;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.error.GTFSError;
import com.conveyal.gtfs.model.FeedInfo;
import com.conveyal.gtfs.validator.model.Priority;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /** Simplified model for storing the first N errors of each type in Mongo. */
    public static class GtfsErrorSummary {
        public String file;
        public Integer line;
        public String field;
        public String message;
        public GtfsErrorSummary () { /* For deserialization. */ }
        public GtfsErrorSummary (GTFSError error) {
            file = error.file;
            line = error.line > 0 ? (int)(error.line) : null;
            field = error.field;
            message = error.getMessage();
        }
    }

    /** Simplified model for storing the first N errors of each type in Mongo. */
    public static class GtfsErrorTypeSummary {
        public String type;
        public int count;
        public List<GtfsErrorSummary> someErrors = new ArrayList<>();
        public Priority priority;
        public GtfsErrorTypeSummary () { /* For deserialization. */ }
        public GtfsErrorTypeSummary (GTFSError error) {
            this.priority = error.priority;
            this.type = error.errorType;
        }
    }

    /** Model for storing summary info in Mongo. Bundle contains one instance of FeedSummary per feed in the Bundle. */
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
        public List<GtfsErrorTypeSummary> errors;

        /** Default empty constructor needed for JSON mapping. */
        public FeedSummary () { }

        public FeedSummary(GTFSFeed feed, String feedGroupId) {
            feedId = feed.feedId;
            bundleScopedFeedId = Bundle.bundleScopeFeedId(feed.feedId, feedGroupId);
            checksum = feed.checksum;
            setServiceDates(feed); // TODO expand to record hours per day by mode.
            createFeedName(feed);
            summarizeErrors(feed);
        }

        /**
         * Set this.name, which seems to only be used for display purposes.
         *
         * If a FeedInfo file is present in the feed, the feed_id, feed_start_date, and feed_end_date are used for the
         * name. If not, dates from calendar/calendar_dates files and agency_name values (up to a limit) are used.
         *
         * This method should be called after setServiceDates().
         */
        private void createFeedName (GTFSFeed feed) {
            String name = null;
            LocalDate startingDate = this.serviceStart;
            LocalDate endingDate = this.serviceEnd;

            if (feed.feedInfo.size() == 1) {
                FeedInfo feedInfo = feed.feedInfo.values().iterator().next();
                if (feedInfo.feed_id != null) name =  feedInfo.feed_id;
                if (feedInfo.feed_start_date != null) startingDate = feedInfo.feed_start_date;
                if (feedInfo.feed_end_date != null) endingDate = feedInfo.feed_end_date;
            }
            if (name == null) {
                int nAgencies = feed.agency.size();
                if (nAgencies > 0) {
                    final int limit = 3;
                    String agencyNames = feed.agency.values().stream().limit(limit)
                            .map(a -> a.agency_name).collect(Collectors.joining(", "));
                    if (nAgencies > limit) {
                        agencyNames += String.format(", +%d more", nAgencies - limit);
                    }
                    name = agencyNames;
                }
            }

            if (name == null) name = "(unknown)";

            this.name = name + ": " + startingDate.toString() + " to " + endingDate.toString(); // ISO-8601
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
         * This summarization could be done on the fly during loading.
         * However some users will want the whole pile of errors.
         */
        private void summarizeErrors (GTFSFeed feed) {
            final int maxErrorsPerType = 10;
            Map<String, GtfsErrorTypeSummary> sortedErrors = new HashMap<>();
            for (GTFSError error : feed.errors) {
                String type = error.errorType;
                GtfsErrorTypeSummary summary = sortedErrors.get(type);
                if (summary == null) {
                    summary = new GtfsErrorTypeSummary(error);
                    sortedErrors.put(type, summary);
                }
                summary.count += 1;
                if (summary.someErrors.size() < maxErrorsPerType) {
                    summary.someErrors.add(new GtfsErrorSummary(error));
                }
            }
            errors = new ArrayList<>(sortedErrors.values());
        }

        public FeedSummary clone () {
            try {
                return (FeedSummary) super.clone();
            } catch (CloneNotSupportedException e) {
                throw AnalysisServerException.unknown(e);
            }
        }
    }

    // The first two PROCESSING_* values are essentially deprecated in favor of Task. Consider eliminating this field.
    public enum Status {
        PROCESSING_OSM,
        PROCESSING_GTFS,
        DONE,
        ERROR
    }
}
