package com.conveyal.r5.analyst.progress;

import com.conveyal.analysis.controllers.UserActivityController;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.models.Model;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.RegionalAnalysis;

/**
 * There is some implicit and unenforced correspondence between these values and those in FileCategory, as well
 * as the tables in Mongo. We should probably clearly state and enforce this parallelism. No background work is
 * done creating regions, projects, or modifications so they don't need to be represented here.
 */
public enum WorkProductType {

    BUNDLE, REGIONAL_ANALYSIS, AGGREGATION_AREA, OPPORTUNITY_DATASET;

    // Currently we have two base classes for db objects so may need to use Object instead of BaseModel parameter
    public static WorkProductType forModel (Model model) {
        if (model instanceof Bundle) return BUNDLE;
        if (model instanceof OpportunityDataset) return OPPORTUNITY_DATASET;
        if (model instanceof RegionalAnalysis) return REGIONAL_ANALYSIS;
        throw new IllegalArgumentException("Unrecognized work product type.");
    }
}
