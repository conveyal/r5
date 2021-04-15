package com.conveyal.r5.analyst.network;

import static com.conveyal.r5.analyst.network.GridGtfsGenerator.GTFS_DATE;

import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.decay.StepDecayFunction;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;

import org.locationtech.jts.geom.Coordinate;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.stream.IntStream;

/**
 * This creates a task for use in tests. It uses a builder pattern but for a non-immutable task
 * object. It provides convenience methods to set all the necessary fields.
 *
 * <p>Usually we would rather search out to freeform pointsets containing a few exact points instead
 * of grid pointsets which will not align exactly with the street intersections. However as of this
 * writing single point tasks could only search out to grids, which are hard-wired into fields of
 * the task, not derived from the pointset object.
 *
 * <p>We may actually want to test with regional tasks to make this less strange, and eventually
 * merge both request types.
 */
public class GridSinglePointTaskBuilder {

    private final GridLayout gridLayout;
    private final AnalysisWorkerTask task;

    public GridSinglePointTaskBuilder(GridLayout gridLayout) {
        this.gridLayout = gridLayout;
        // We will accumulate settings into this task.
        task = new TravelTimeSurfaceTask();
        task.date = GTFS_DATE;
        // Set defaults that can be overridden by calling builder methods.
        task.accessModes = EnumSet.of(LegMode.WALK);
        task.egressModes = EnumSet.of(LegMode.WALK);
        task.directModes = EnumSet.of(LegMode.WALK);
        task.transitModes = EnumSet.allOf(TransitModes.class);
        // Override the percentiles to get min, 25, median, 75, max.
        // Max percentiles is limited to 5 so we can't return all 100 of them.
        // Our percentile definition will yield an index of -1 for percentile zero.
        // But in a list of more than 100 items, percentile 1 and 99 will return the first and last
        // elements.
        task.percentiles = DistributionTester.PERCENTILES;
        // In single point tasks all 121 cutoffs are required (there is a check).
        task.cutoffsMinutes = IntStream.rangeClosed(0, 120).toArray();
        task.decayFunction = new StepDecayFunction();
        task.monteCarloDraws = 1200; // Ten per minute over a two hour window.
        // By default, traverse one block in a round predictable number of seconds.
        task.walkSpeed =
                gridLayout.streetGridSpacingMeters / gridLayout.walkBlockTraversalTimeSeconds;
        // Record more detailed information to allow comparison to theoretical travel time
        // distributions.
        task.recordTravelTimeHistograms = true;
    }

    public GridSinglePointTaskBuilder setOrigin(int gridX, int gridY) {
        Coordinate origin = gridLayout.getIntersectionLatLon(gridX, gridY);
        task.fromLat = origin.y;
        task.fromLon = origin.x;
        return this;
    }

    public GridSinglePointTaskBuilder setDestination(int gridX, int gridY) {
        Coordinate destination = gridLayout.getIntersectionLatLon(gridX, gridY);
        task.destinationPointSets = new PointSet[] {new FreeFormPointSet(destination)};
        task.destinationPointSetKeys = new String[] {"ID"};
        return this;
    }

    public GridSinglePointTaskBuilder morningPeak() {
        task.fromTime = LocalTime.of(7, 00).toSecondOfDay();
        task.toTime = LocalTime.of(9, 00).toSecondOfDay();
        return this;
    }

    public GridSinglePointTaskBuilder uniformOpportunityDensity(double density) {
        Grid grid = gridLayout.makeUniformOpportunityDataset(density);
        task.destinationPointSets = new PointSet[] {grid};
        task.destinationPointSetKeys = new String[] {"GRID"};

        // In a single point task, the grid of destinations is given with these fields, not from the
        // pointset object.
        // The destination point set (containing the opportunity densities) must then match these
        // same dimensions.
        task.zoom = grid.zoom;
        task.north = grid.north;
        task.west = grid.west;
        task.width = grid.width;
        task.height = grid.height;

        return this;
    }

    public GridSinglePointTaskBuilder monteCarloDraws(int draws) {
        task.monteCarloDraws = draws;
        return this;
    }

    public AnalysisWorkerTask build() {
        return task;
    }
}
