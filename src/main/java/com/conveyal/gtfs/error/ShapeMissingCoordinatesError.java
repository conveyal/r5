package com.conveyal.gtfs.error;

import com.conveyal.gtfs.model.ShapePoint;
import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/**
 * Created by landon on 5/2/16.
 */
public class ShapeMissingCoordinatesError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public final Priority priority = Priority.MEDIUM;
    public final String[] tripIds;

    public ShapeMissingCoordinatesError(ShapePoint shapePoint, String[] tripIds) {
        super("shapes", shapePoint.sourceFileLine, "shape_id", shapePoint.shape_id);
        this.tripIds = tripIds;
    }

    @Override public String getMessage() {
        return "Shape " + affectedEntityId + " is missing coordinates (affects " + tripIds.length + " trips)";
    }
}