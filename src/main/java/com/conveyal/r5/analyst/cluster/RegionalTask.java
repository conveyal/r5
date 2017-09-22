package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.BootstrappingTravelTimeReducer;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.GridCache;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.transit.TransportNetwork;

import java.io.OutputStream;

/**
 * Represents a task to be performed as part of a regional analysis.
 */
public class RegionalTask extends AnalysisTask implements Cloneable {
    /**
     * Coordinates of origin cell in grid defined in AnalysisTask.
     *
     * Note that these do not override fromLat and fromLon; those must still be set separately. This is for future use
     * to allow use of arbitrary origin points.
     */
    public int x = -1, y = -1;

    /** The grid key on S3 to compute access to */
    public String grid;

    /** Where should output of this job be saved */
    public String outputQueue;

    /** The grid we are calculating accessibility to */
    private transient Grid gridData;

    @Override
    public Type getType() {
        return Type.REGIONAL_ANALYSIS;
    }

    @Override
    public boolean isHighPriority() {
        return false; // regional analysis tasks are not high priority
    }

    /** Regional analyses use the extents of the destination opportunity grid as their destination extents */
    @Override
    public PointSet getDestinations(TransportNetwork network, GridCache gridCache) {
        this.gridData = gridCache.get(this.grid);
        // Use the network point set as the base point set, so that the cached linkages are used
        return gridPointSetCache.get(gridData, network.gridPointSet);
    }

    /** Use a bootstrapping reducer for a regional analysis task */
    @Override
    public PerTargetPropagater.TravelTimeReducer getTravelTimeReducer(TransportNetwork network, OutputStream os) {
        return new BootstrappingTravelTimeReducer(this, gridData);
    }

    public RegionalTask clone () {
        return (RegionalTask) super.clone();
    }
}
