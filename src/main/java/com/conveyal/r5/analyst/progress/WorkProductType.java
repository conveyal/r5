package com.conveyal.r5.analyst.progress;

import com.conveyal.analysis.models.AggregationArea;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.models.SpatialDataSource;

/**
 * There is some implicit and unenforced correspondence between these values and those in FileCategory, as well
 * as the tables in Mongo. We should probably clearly state and enforce this correspondance. No background work is
 * done creating regions, projects, or modifications so they don't need to be represented here.
 */
public enum WorkProductType {

    BUNDLE, REGIONAL_ANALYSIS, AGGREGATION_AREA, OPPORTUNITY_DATASET, DATA_SOURCE;

    public static WorkProductType forModel (Object model) {
        if (model instanceof Bundle) return BUNDLE;
        if (model instanceof OpportunityDataset) return OPPORTUNITY_DATASET;
        if (model instanceof RegionalAnalysis) return REGIONAL_ANALYSIS;
        if (model instanceof AggregationArea) return AGGREGATION_AREA;
        if (model instanceof DataSource) return DATA_SOURCE;
        throw new IllegalArgumentException("Unrecognized work product type.");
    }

}
