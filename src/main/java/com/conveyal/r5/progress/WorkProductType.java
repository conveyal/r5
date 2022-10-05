package com.conveyal.r5.progress;

/**
 * There is some implicit and unenforced correspondence between these values and those in FileCategory, as well
 * as the tables in MongoDB. We should probably clearly state and enforce this correspondence. No background work is
 * done creating regions, projects, or modifications so they don't need to be represented here.
 */
public enum WorkProductType {

    BUNDLE, REGIONAL_ANALYSIS, AGGREGATION_AREA, OPPORTUNITY_DATASET, DATA_SOURCE;
}
