package com.conveyal.osmlib;

/** A generic exception representing any problem encountered within Conveyal osm-lib related code. */
public class OsmLibException extends RuntimeException {

    public OsmLibException (String message) {
        super(message);
    }

    public OsmLibException (String message, Throwable cause) {
        super(message, cause);
    }

}
