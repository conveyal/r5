package com.conveyal.gtfs.error;

import com.conveyal.gtfs.model.Trip;
import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/**
 * Created by landon on 5/6/16.
 */
public class ReversedTripShapeError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public final String shapeId;

    public ReversedTripShapeError(Trip trip) {
        super("trips", trip.sourceFileLine, "shape_id", Priority.HIGH, trip.trip_id);
        this.shapeId = trip.shape_id;
    }

    @Override public String getMessage() {
        return "Trip " + affectedEntityId + " references reversed shape " + shapeId;
    }
}
