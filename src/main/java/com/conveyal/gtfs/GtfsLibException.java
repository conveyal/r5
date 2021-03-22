package com.conveyal.gtfs;

/** A generic exception for errors encountered within Conveyal GTFS loading and manipulation code. */
public class GtfsLibException extends RuntimeException {

    public GtfsLibException (String message) {
        super(message);
    }

    public GtfsLibException (String message, Throwable cause) {
        super(message, cause);
    }

}
