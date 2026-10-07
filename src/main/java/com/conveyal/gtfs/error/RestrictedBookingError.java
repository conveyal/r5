package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

/// Indicates that the feed contains booking rules that restrict use of a service based on rider
/// characteristics that we do not model. Service availability will be overestimated for people
/// who do not qualify to use this service.
public class RestrictedBookingError extends GTFSError {

    /// Feeds can still be used for routing even if we can't clearly decide who can use them.
    public static final Priority PRIORITY = Priority.MEDIUM;
    private static final String MESSAGE = "Flex stop_time references a booking rule. This service may not be available to most riders.";

    public RestrictedBookingError (String file, long line) {
        super(file, line, "*_booking_rule_id");
    }

    @Override public String getMessage() {
        return MESSAGE;
    }

    @Override public Priority getPriority() {
        return PRIORITY;
    }

}
