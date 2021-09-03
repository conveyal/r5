package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.file.FileStorageKey;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.bson.codecs.pojo.annotations.BsonIgnore;

import static com.conveyal.file.FileCategory.GRIDS;

/**
 * An aggregation area defines a set of origin points that can be combined to produce an aggregate accessibility figure.
 * For example, if we have accessibility results for an entire city, we might want to calculate 25th percentile
 * population-weighted accessibility for each administrative district. Each neighborhood would be an aggreagation area.
 *
 * An aggregation area is defined by a polygon that has been rasterized and stored as a grid, with each pixel value
 * expressing how much of that pixel falls within the mask polygon. These values are in the range of 0 to 100,000
 * (rather than 0 to 1) because our serialized (on-disk) grid format can only store integers.
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
