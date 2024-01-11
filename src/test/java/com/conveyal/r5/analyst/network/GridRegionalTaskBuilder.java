package com.conveyal.r5.analyst.network;

import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.decay.StepDecayFunction;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import org.locationtech.jts.geom.Coordinate;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.stream.IntStream;

import static com.conveyal.r5.analyst.WebMercatorExtents.DEFAULT_ZOOM;
import static com.conveyal.r5.analyst.network.GridGtfsGenerator.WEEKDAY_DATE;
import static com.conveyal.r5.analyst.network.GridGtfsGenerator.WEEKEND_DATE;

/**
 * This creates a task for use in tests. It uses a builder pattern but modifies a non-immutable task object. It
 * provides convenience methods to set all the necessary fields. This builder may be reused to produce several tasks in
 * a row with different settings, but only use the most recently produced one at any time. See build() for further
 * explanation. We want to use a limited number of destinations at exact points instead of Mercator gridded
 * destinations, which would not be exactly aligned with the desert grid. Therefore we create regional tasks rather
 * than single-point TravelTimeSurfaceTasks because single-point tasks always have gridded destinations (they always
 * return gridded travel times which must be exactly aligned with any accessibility destinations).
 */
public class GridRegionalTaskBuilder {

    public static final int DEFAULT_MONTE_CARLO_DRAWS = 4800; // 40 per minute over a two hour window.
    private final GridLayout gridLayout;

    private final RegionalTask task;

    public GridRegionalTaskBuilder(GridLayout gridLayout) {
        this.gridLayout = gridLayout;
        // We will accumulate settings into this task.
        task = new RegionalTask();
        task.date = WEEKDAY_DATE;
        // Set defaults that can be overridden by calling builder methods.
        task.accessModes = EnumSet.of(LegMode.WALK);
        task.egressModes = EnumSet.of(LegMode.WALK);
        task.directModes = EnumSet.of(LegMode.WALK);
        task.transitModes = EnumSet.allOf(TransitModes.class);
        // Override the percentiles to get min, 25, median, 75, max.
        // Max percentiles is limited to 5 so we can't return all 100 of them.
        // Our percentile definition will yield an index of -1 for percentile zero.
        // But in a list of more than 100 items, percentile 1 and 99 will return the first and last elements.
        task.percentiles = DistributionTester.PERCENTILES;
        // In single point tasks all 121 cutoffs are required (there is a check).
        task.cutoffsMinutes = IntStream.rangeClosed(0, 120).toArray();
        task.decayFunction = new StepDecayFunction();
        task.monteCarloDraws = DEFAULT_MONTE_CARLO_DRAWS;
        // By default, traverse one block in a round predictable number of seconds.
        task.walkSpeed = gridLayout.streetGridSpacingMeters / gridLayout.walkBlockTraversalTimeSeconds;
        // Unlike single point tasks, travel time recording must be enabled manually on regional tasks.
        task.recordTimes = true;
        // Record more detailed information to allow comparison to theoretical travel time distributions.
        task.recordTravelTimeHistograms = true;
        // Set the grid extents on the task, otherwise the task will fail checks on the grid dimensions and zoom level.
        WebMercatorExtents extents = WebMercatorExtents.forWgsEnvelope(gridLayout.gridEnvelope(), DEFAULT_ZOOM);
        task.zoom = extents.zoom;
        task.north = extents.north;
        task.west = extents.west;
        task.width = extents.width;
        task.height = extents.height;
    }

    public GridRegionalTaskBuilder setOrigin (int gridX, int gridY) {
        Coordinate origin = gridLayout.getIntersectionLatLon(gridX, gridY);
        task.fromLat = origin.y;
        task.fromLon = origin.x;
        return this;
    }

    public GridRegionalTaskBuilder weekdayMorningPeak () {
        task.date = WEEKDAY_DATE;
        morningPeak();
        return this;
    }

    public GridRegionalTaskBuilder weekendMorningPeak () {
        task.date = WEEKEND_DATE;
        morningPeak();
        return this;
    }

    public GridRegionalTaskBuilder morningPeak () {
        task.fromTime = LocalTime.of(7, 00).toSecondOfDay();
        task.toTime = LocalTime.of(9, 00).toSecondOfDay();
        return this;
    }

    public GridRegionalTaskBuilder departureTimeWindow(int startHour, int startMinute, int durationMinutes) {
        task.fromTime = LocalTime.of(startHour, startMinute).toSecondOfDay();
        task.toTime = LocalTime.of(startHour, startMinute + durationMinutes).toSecondOfDay();
        return this;
    }

    public GridRegionalTaskBuilder maxRides(int rides) {
        task.maxRides = rides;
        return this;
    }

    /**
     * When trying to verify more complex distributions, the Monte Carlo approach may introduce too much noise.
     * Increasing the number of draws will yield a better approximation of the true travel time distribution
     * (while making the tests run slower).
     */
    public GridRegionalTaskBuilder monteCarloDraws (int draws) {
        task.monteCarloDraws = draws;
        return this;
    }

    /**
     * Create a FreeformPointSet with a single point in it situated at the specified street intersection, and embed
     * that PointSet in the request. In normal usage supplying FreeformPointSets as destination is only done for
     * regional analysis tasks, but a testing code path exists to handle their presence on single point requests.
     * This eliminates any difficulty estimating the final segment of egress, walking from the street to a gridded
     * travel time sample point. Although egress time is something we'd like to test too, it is not part of the transit
     * routing we're concentrating on here, and will vary as the Simpson Desert street grid does not align with our
     * web Mercator grid pixels. Using a single measurement point also greatly reduces the amount of travel time
     * histograms that must be computed and retained, improving the memory and run time cost of tests.
     */
    public GridRegionalTaskBuilder singleFreeformDestination(int x, int y) {
        FreeFormPointSet ps = new FreeFormPointSet(gridLayout.getIntersectionLatLon(x, y));
        // Downstream code expects to see the same number of keys and PointSet objects so initialize both.
        task.destinationPointSetKeys = new String[] { "POINT_SET" };
        task.destinationPointSets = new PointSet[] { ps };
        return this;
    }

    /**
     * Produce a new AnalysisWorkerTask object based on the settings applied to this builder. The builder remains
     * valid after this operation, and later calls to build() after further modifying the settings will return
     * separate AnalysisWorkerTask objects. HOWEVER despite the use of clone(), these task objects share references
     * to some sub-objects such as arrays or Sets. To avoid problems, only use the most recently produced task from
     * the builder. Do not continue using tasks produced by previous calls to build().
     */
    public AnalysisWorkerTask build () {
        return task.clone();
    }

}
