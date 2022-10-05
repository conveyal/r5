package com.conveyal.r5.analyst;

/**
 * Instances are serialized and sent from the backend to workers processing single point,
 * interactive tasks usually originating from the Analysis UI, and returning a surface of travel
 * times to each destination. Several travel times to each destination are returned, representing
 * selected percentiles of all travel times from the chosen origin to that destination.
 *
 * TODO rename to something like SinglePointTask because these can now return accessibility, travel time, paths, etc.
 */
public class TravelTimeSurfaceTask extends AnalysisWorkerTask {

    // FIXME red flag - what is this enum enumerating Java types?

    @Override
    public Type getType() {
        return Type.TRAVEL_TIME_SURFACE;
    }

    /** Default format is a Conveyal flat binary file */
    private TravelTimeSurfaceResultsFormat format = TravelTimeSurfaceResultsFormat.GRID;

    public void setFormat(TravelTimeSurfaceResultsFormat format){
        this.format = format;
    }

    public TravelTimeSurfaceResultsFormat getFormat(){
        return format;
    }

    @Override
    public int nTargetsPerOrigin () {
        // In TravelTimeSurfaceTasks, the set of destinations is always determined by the web mercator extents.
        return width * height;
    }

}
