package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/** Represents a problem parsing a time of day field of GTFS feed. */
public class TimeParseError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public TimeParseError(String file, long line, String field) {
        super(file, line, field, Priority.MEDIUM);
    }

    @Override public String getMessage() {
        return "Could not parse time (format should be HH:MM:SS).";
    }

}
