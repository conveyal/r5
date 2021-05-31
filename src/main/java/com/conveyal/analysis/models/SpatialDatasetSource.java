package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.spatial.SpatialDataset.GeometryType;
import com.conveyal.analysis.spatial.SpatialDataset.SourceFormat;

import java.util.Map;

public class SpatialDatasetSource extends BaseModel {
    public String regionId;
    public String description;
    public SourceFormat sourceFormat;
    public GeometryType geometryType;
    public Map<String, Class> attributes;

    private SpatialDatasetSource (UserPermissions userPermissions, String sourceName) {
        super(userPermissions, sourceName);
    }

    public static SpatialDatasetSource create (UserPermissions userPermissions, String sourceName) {
        return new SpatialDatasetSource(userPermissions, sourceName);
    }

    public SpatialDatasetSource withRegion (String regionId) {
        this.regionId = regionId;
        return this;
    }

}
