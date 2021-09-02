package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.file.FileStorageKey;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.bson.codecs.pojo.annotations.BsonIgnore;

import static com.conveyal.file.FileCategory.GRIDS;

/**
 * An aggregation area defines a set of origin points to be averaged together to produce an aggregate accessibility figure.
 * It is defined by a geometry that is rasterized and stored as a grid, with pixels with values between 0 and 100,000
 * depending on how much of that pixel is overlapped by the mask.
 */
public class AggregationArea extends BaseModel {

    public String regionId;
    public String dataSourceId;
    public String dataGroupId;

    /** Zero-argument constructor required for Mongo automatic POJO deserialization. */
    public AggregationArea () { }

    public AggregationArea(UserPermissions user, String name, SpatialDataSource dataSource) {
        super(user, name);
        this.regionId = dataSource.regionId;
        this.dataSourceId = dataSource._id.toString();
    }

    @JsonIgnore
    @BsonIgnore
    public String getS3Key () {
        return String.format("%s/mask/%s.grid", regionId, _id);
    }

    @JsonIgnore
    @BsonIgnore
    public FileStorageKey getStorageKey () {
        // These in the GRIDS file storage category because aggregation areas are masks represented as binary grids.
        return new FileStorageKey(GRIDS, getS3Key());
    }

}
