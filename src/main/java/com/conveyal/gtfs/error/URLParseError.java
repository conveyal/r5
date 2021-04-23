package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/** Represents a problem parsing a URL field from a GTFS feed. */
public class URLParseError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public URLParseError(String file, long line, String field) {
        super(file, line, field, Priority.LOW);
    }

    @Override public String getMessage() {
        return "Could not parse URL (format should be <scheme>://<authority><path>?<query>#<fragment>).";
    }

}
