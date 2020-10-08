package com.conveyal.gtfs.error;

import com.conveyal.gtfs.model.Trip;
import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/**
 * Created by landon on 5/6/16.
 */
public class MissingShapeError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public final Priority priority = Priority.MEDIUM;

    public MissingShapeError(Trip trip) {
        super("trips", trip.sourceFileLine, "shape_id", trip.trip_id);
    }

    @Override public String getMessage() {
        return "Trip " + affectedEntityId + " is missing a shape";
    }
}
