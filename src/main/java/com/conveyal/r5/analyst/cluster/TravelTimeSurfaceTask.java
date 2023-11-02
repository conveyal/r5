package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.profile.ProfileRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;

import static com.conveyal.r5.common.Util.isNullOrEmpty;
import static com.google.common.base.Preconditions.checkState;

/**
 * Instances are serialized and sent from the backend to workers processing single point,
 * interactive tasks usually originating from the Analysis UI, and returning a surface of travel
 * times to each destination. Several travel times to each destination are returned, representing
 * selected percentiles of all travel times from the chosen origin to that destination.
 *
 * TODO rename to something like SinglePointTask because these can now return accessibility, travel time, paths, etc.
 */
public class TravelTimeSurfaceTask extends AnalysisWorkerTask {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

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
        // In TravelTimeSurfaceTasks, the set of destinations is always determined by the web mercator extents in the
        // request. A single WebMercatorGridPointSet is created with those extents. A single small freeform PointSet may
        // also be present, but only in testing situations. The checkState assertions serve to verify assumptions that
        // destinationPointSets are always set and we can polymorphically fetch the number of items for all normal and
        // testing use cases. Once that is clearly working the assertions could be removed.
        checkState(!isNullOrEmpty(destinationPointSets), "Expected destination PointSets to be present.");
        checkState(destinationPointSets.length == 1, "Expected a single destination PointSet in TravelTimeSurfaceTask.");
        PointSet destinations = destinationPointSets[0];
        int nFeatures = destinations.featureCount();
        if (destinations instanceof FreeFormPointSet) {
            LOG.warn("Should currently be used only in testing: FreeFormPointSet specified in TravelTimeSurfaceTask.");
            checkState(nFeatures == 1);
        } else {
            checkState(nFeatures == width * height);
        }
        return nFeatures;
    }

}
