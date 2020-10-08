package com.conveyal.gtfs.error;

import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/** Indicates that a stop exists more than once in the feed. */
public class UnusedStopError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public final Priority priority;
    public final Stop stop;

    public UnusedStopError(Stop stop) {
        super("stops", stop.sourceFileLine, "stop_id", stop.stop_id);
        this.priority = Priority.LOW;
        this.stop = stop;
    }

    @Override public String getMessage() {
        return String.format("Stop Id %s is not used in any trips.", affectedEntityId);
    }
}
