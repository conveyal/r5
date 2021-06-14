package com.conveyal.analysis.spatial;

import com.conveyal.analysis.models.AggregationArea;
import com.conveyal.r5.analyst.progress.Task;

import java.io.File;
import java.util.ArrayList;

public class Polygons extends GeometryWrapper {

    public Polygons (int featureCount) {
        super(featureCount);
    }

    public static ArrayList<AggregationArea> toAggregationAreas (File file, SpatialDataset.SourceFormat sourceFormat,
                                                                 Task progressListener) {
        ArrayList<AggregationArea> aggregationAreas = new ArrayList<>();
        // TODO from shapefile
        // TODO from geojson
        return aggregationAreas;
    }

    // TODO toGrid from shapefile and geojson

    // TODO modification polygon

}
