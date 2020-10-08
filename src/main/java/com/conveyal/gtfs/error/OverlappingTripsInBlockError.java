package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/**
 * Created by landon on 5/6/16.
 */
public class OverlappingTripsInBlockError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public final String[] tripIds;
    public final Priority priority = Priority.HIGH;
    public final String routeId;

    public OverlappingTripsInBlockError(long line, String field, String affectedEntityId, String routeId, String[] tripIds) {
        super("trips", line, field, affectedEntityId);
        this.tripIds = tripIds;
        this.routeId = routeId;
    }

    @Override public String getMessage() {
        return String.format("Trip Ids %s overlap (route: %s) and share block ID %s", String.join(" & ", tripIds), routeId, affectedEntityId);
    }
}
