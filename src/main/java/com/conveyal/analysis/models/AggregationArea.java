package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.file.FileStorageKey;
import com.fasterxml.jackson.annotation.JsonIgnore;

import static com.conveyal.file.FileCategory.GRIDS;

/**
 * An aggregation area defines a set of origin points to be averaged together to produce an aggregate accessibility figure.
 * It is defined by a geometry that is rasterized and stored as a grid, with pixels with values between 0 and 100,000
 * depending on how much of that pixel is overlapped by the mask.
 */
public class AggregationArea extends BaseModel {
    public String regionId;
    public String sourceId;

    private AggregationArea(UserPermissions user, String name) {
        super(user, name);
    }

    // FLUENT METHODS FOR CONFIGURING

    /** Call this static factory to begin building a task. */
    public static AggregationArea create (UserPermissions user, String name) {
        return new AggregationArea(user, name);
    }

    public AggregationArea withSource (SpatialDataSource source) {
        this.regionId = source.regionId;
        this.sourceId = source._id.toString();
        return this;
    }

    @JsonIgnore
    public String getS3Key () {
        return String.format("%s/mask/%s.grid", regionId, _id);
    }

    @JsonIgnore
    public FileStorageKey getStorageKey () {
        // These in the GRIDS file storage category because aggregation areas are masks represented as binary grids.
        return new FileStorageKey(GRIDS, getS3Key());
    }

}
