package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

/**
 * Created by landon on 5/2/17.
 */
public class NoAgencyInFeedError extends GTFSError {
    public final Priority priority = Priority.HIGH;

    public NoAgencyInFeedError() {
        super("agency", 0, "agency_id");
    }

    @Override public String getMessage() {
        return String.format("No agency listed in feed (must have at least one).");
    }
}
