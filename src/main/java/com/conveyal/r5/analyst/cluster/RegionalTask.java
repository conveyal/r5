package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.BootstrappingTravelTimeReducer;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.GridCache;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.TravelTimeSurfaceReducer;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.transit.TransportNetwork;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * The grid key on S3 to compute access to. If this is not blank, the default TravelTimeSurfaceTask will be
     * overridden; returnInVehicleTimes, returnWaitTimes, and returnPaths will be set to false; and the returned results
     * will be an accessibility value per origin, rather than a grid of travel times from that origin.
     */
    public String grid;

    /**
     * An array of grid keys on S3 to compute access to. If this is not blank, the default TravelTimeSurfaceTask will be
     * overridden; returnInVehicleTimes, returnWaitTimes, and returnPaths will be set to false; and the returned results
     * will be an accessibility value per origin for each destination grid, rather than a grid of travel times from
     * that origin.
     * NOT YET IMPLEMENTED AND TESTED
     */
    public List <String> grids;

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

    /**
     * Regional analyses use the extents of the destination opportunity grids as their destination extents.
     * We don't want to enqueue duplicate tasks with the same destination pointset extents, because it is more efficient
     * to compute travel time for a given destination only once, then accumulate multiple accessibility values
     * for multiple opportunities at that destination.
     */
    @Override
    public List<PointSet> getDestinations(TransportNetwork network, GridCache gridCache) {
        List<Grid> gridList = new ArrayList<>();
        List<PointSet> pointSets = new ArrayList<>();

        if (makeStaticSite) {
            // In the special case where we're making a static site, a regional task is producing travel time grids.
            // This is unlike the usual case where regional tasks produce accessibility indicator values.
            // Because the time grids are not intended for one particular set of destinations,
            // they should cover the whole analysis region.
            // FIXME this limits the destination grid bounds to be exactly those of the origin grid.
            pointSets.add(gridPointSetCache.get(this.zoom, this.west, this.north, this.width, this.height, network.gridPointSet));
            return pointSets;
        }

        if (grid != null){
            // A single grid is specified.
            gridData = gridCache.get(grid);
            pointSets.add(gridPointSetCache.get(gridData, network.gridPointSet));
        } else {
            // Multiple grids should be specified. Add only the first one, and any with different extents, to pointSets.
            // TODO more explanation, complete implementation. This block is currently unused.
            // FIXME we really shouldn't have two different implementations present, one for a list and one for a single grid.
            gridData = gridCache.get(grids.get(0));
            gridList.add(gridData);
            pointSets.add(gridPointSetCache.get(gridData, network.gridPointSet));

            for (int i = 1; i < grids.size(); i++) { // the first grid is already in the list
                gridData = gridCache.get(grids.get(i));

                for (int j = 0; j < i; j++) { // loop over previously added grids
                    if (gridData.hasEqualExtents(gridList.get(j))) break;

                    if (j == i - 1) { // all previously added grids checked, none matches extents, so add it
                        gridList.add(gridData);
                        pointSets.add(gridPointSetCache.get(gridData, network.gridPointSet));
                    }
                }
            }
        }
        // Use the network point set as the base point set, so that the cached linkages are used
        return pointSets;
    }

    /** Use the standard single-point TravelTimeSurfaceReducer if no grid has been specified in the request.
     * Otherwise, use a reducer that returns accessibility values. */
    @Override
    public PerTargetPropagater.TravelTimeReducer getTravelTimeReducer(TransportNetwork network, OutputStream os) {
        if (gridData == null || makeStaticSite) {
            // When making a static site, we want to save travel time surfaces for no particular grid.
            return new TravelTimeSurfaceReducer(this, network, os);
        } else {
            return new BootstrappingTravelTimeReducer(this, gridData);
        }
    }

    public RegionalTask clone () {
        return (RegionalTask) super.clone();
    }
}
