package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.WebMercatorExtents;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Instances are serialized and sent from the backend to workers processing single point,
 * interactive tasks usually originating from the Analysis UI, and returning a surface of travel
 * times to each destination. Several travel times to each destination are returned, representing
 * selected percentiles of all travel times from the chosen origin to that destination.
 */
public class TravelTimeSurfaceTask extends AnalysisTask {

    // FIXME red flag - what is this enum enumerating Java types?

    @Override
    public Type getType() {
        return Type.TRAVEL_TIME_SURFACE;
    }

    @JsonIgnoreProperties(ignoreUnknown=true)

    /** Whether to download as a Conveyal flat binary file for display in analysis-ui, or a geotiff */
    public enum Format {
        /** Flat binary grid format */
        GRID,
        /** GeoTIFF file for download and use in GIS */
        GEOTIFF
    }

    /** Default format is a Conveyal flat binary file */
    private Format format = Format.GRID;

    public void setFormat(Format format){
        this.format = format;
    }

    public Format getFormat(){
        return format;
    }

    @Override
    public WebMercatorExtents getWebMercatorExtents() {
        return WebMercatorExtents.forTask(this);
    }

    @Override
    public int nTargetsPerOrigin () {
        // In TravelTimeSurfaceTasks, the set of destinations is always determined by the web mercator extents.
        return width * height;
    }

}
