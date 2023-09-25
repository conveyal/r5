package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/**
 * Indicates that a stop is in a suspect location, for example in a place like a desert where there are not enough
 * people to support public transit. This can be the result of incorrect coordinate transforms into WGS84.
 * Stops are often located on "null island" at (0,0). This can also happen in other coordinate systems before they
 * are transformed to WGS84: the origin of a common French coordinate system is in the Sahara.
 */
public class SuspectStopLocationError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public SuspectStopLocationError(String stopId, long line) {
        super("stops", line, "stop_id", stopId);
    }

    @Override public String getMessage() {
        return String.format(
            "Stop with ID %s is in a sparsely populated area (fewer than 5 inhabitants per square km in any " +
                    "neighboring 1/4 degree cell)",
            affectedEntityId
        );
    }

    @Override public Priority getPriority() {
        return Priority.MEDIUM;
    }
}
