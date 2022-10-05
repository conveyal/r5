package com.conveyal.r5.analyst;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)

/** Whether to download as a Conveyal flat binary file for display in analysis-ui, or a geotiff */
public enum TravelTimeSurfaceResultsFormat {
    /**
     * Flat binary grid format
     */
    GRID,
    /**
     * GeoTIFF file for download and use in GIS
     */
    GEOTIFF
}
