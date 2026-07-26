package com.conveyal.r5.analyst.network;

import com.conveyal.analysis.models.CsvResultOptions;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.decay.StepDecayFunction;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.common.JsonUtilities;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.IntStream;

import static com.conveyal.r5.analyst.WebMercatorExtents.DEFAULT_ZOOM;
import static com.conveyal.r5.analyst.network.GridGtfsGenerator.WEEKDAY_DATE;
import static com.conveyal.r5.analyst.network.GridGtfsGenerator.WEEKEND_DATE;

/// Creates a task for use in tests using a builder pattern that modifies a non-immutable private
/// task object, which is copied to make the task instances actually used in tests. Each built task
/// is a deep copy made by round-tripping the private task object through its JSON wire
/// representation, so the builder may be reused to produce several tasks in a row with
/// incrementally different settings, and every produced task remains independently usable.
/// See buildForType() for further explanation.
///
/// We often want to use a limited number of destinations at exact points instead of Mercator
/// gridded destinations, as the Mercator grid does not align with the desert street grid,
/// complicating theoretical prediction of which destinations are reached. Freeform destinations are
/// only supported on regional tasks, not single-point tasks. Single-point tasks always return
/// gridded travel times exactly aligned with an accessibility destination grid. Therefore, most
/// tests of access to opportunities build and process RegionalTasks rather than single-point
/// TravelTimeSurfaceTasks, even if they are only checking one or two origin points.
///
/// Destination PointSets registered on this builder are attached to each built task using the same
/// loading and wrapping code path used in production, applying the production code that handles
/// opportunity grids whose extents differ from the task's extents.
public class GridTaskBuilder {

    /// 40 draws per minute over a two hour window.
    public static final int DEFAULT_MONTE_CARLO_DRAWS = 40 * 60 * 2;

    private final GridLayout gridLayout;

    /// Settings accumulate into this regional task. When building a RegionalTask or single-point TravelTimeSurfaceTask,
    /// all relevant settings are copied into a fresh instance. Fields this builder never modifies are left at their
    /// default values on both task types (inherited from their shared superclasses).
    private final RegionalTask task;

    /// Destination PointSets are registered here by key and loaded onto each built task by key, as in production.
    private final TestPointSetCache pointSetCache = new TestPointSetCache();

    private final List<String> destinationKeys = new ArrayList<>();

    public GridTaskBuilder (GridLayout gridLayout) {
        this.gridLayout = gridLayout;
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
        task.walkSpeed = (float) gridLayout.streetGridSpacingMeters / gridLayout.walkBlockTraversalTimeSeconds;
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

    /// Set the origin to the street intersection at the given grid coordinates.
    public GridTaskBuilder setOrigin (int gridX, int gridY) {
        Coordinate origin = gridLayout.getIntersectionLatLon(gridX, gridY);
        task.fromLat = origin.y;
        task.fromLon = origin.x;
        return this;
    }

    public GridTaskBuilder weekdayMorningPeak () {
        task.date = WEEKDAY_DATE;
        morningPeak();
        return this;
    }

    public GridTaskBuilder weekendMorningPeak () {
        task.date = WEEKEND_DATE;
        morningPeak();
        return this;
    }

    public static final LocalTime MORNING_PEAK_START = LocalTime.of(7, 0);
    public static final LocalTime MORNING_PEAK_END = LocalTime.of(9, 0);

    public GridTaskBuilder morningPeak () {
        task.fromTime = MORNING_PEAK_START.toSecondOfDay();
        task.toTime = MORNING_PEAK_END.toSecondOfDay();
        return this;
    }

    public GridTaskBuilder departureTimeWindow(int startHour, int startMinute, int durationMinutes) {
        task.fromTime = LocalTime.of(startHour, startMinute).toSecondOfDay();
        task.toTime = LocalTime.of(startHour, startMinute + durationMinutes).toSecondOfDay();
        return this;
    }

    public GridTaskBuilder maxRides(int rides) {
        task.maxRides = rides;
        return this;
    }

    /// When trying to verify more complex distributions, the Monte Carlo approach may introduce too much noise.
    /// Increasing the number of draws will yield a better approximation of the true travel time distribution (while
    /// making the tests run slower). In the future, seeding the random number generator could avoid these problems.
    public GridTaskBuilder monteCarloDraws (int draws) {
        task.monteCarloDraws = draws;
        return this;
    }

    /// Record detailed path information at each destination, as returned to the broker for CSV path results.
    public GridTaskBuilder includePathResults () {
        task.includePathResults = true;
        task.csvResultOptions = new CsvResultOptions();
        return this;
    }

    /// Record accessibility indicator values on regional tasks. Not needed with buildSinglePoint,
    /// as single point tasks compute accessibility whenever destination PointSets are present.
    public GridTaskBuilder recordAccessibility () {
        task.recordAccessibility = true;
        return this;
    }

    /// Replace the default cutoffs (every minute from 0 to 120) with the specified cutoffs, in minutes.
    /// Only valid for regional tasks, which allow a small number of cutoffs. Single point tasks require the default.
    public GridTaskBuilder cutoffsMinutes (int... cutoffsMinutes) {
        task.cutoffsMinutes = cutoffsMinutes;
        return this;
    }

    /// Create a FreeformPointSet with a single point in it situated at the specified street intersection, and embed
    /// that PointSet in the request. In normal usage supplying FreeFormPointSets as destination is only done for
    /// regional analysis tasks, but a testing code path exists to handle their presence on single point requests.
    /// This eliminates any difficulty estimating the final segment of egress, walking from the street to a gridded
    /// travel time sample point. Although egress time is something we'd like to test too, it is not part of the transit
    /// routing we're concentrating on here, and will vary as the Simpson Desert street grid does not align with our
    /// web Mercator grid pixels. Using a single measurement point also greatly reduces the amount of travel time
    /// histograms that must be computed and retained, improving the memory and run time cost of tests.
    public GridTaskBuilder singleFreeformDestination(int x, int y) {
        return freeformDestinations(new FreeFormPointSet(gridLayout.getIntersectionLatLon(x, y)));
    }

    /// Use the supplied FreeFormPointSet as the destinations, with its opportunity counts as the accessibility
    /// opportunities. Replaces any previously registered destinations. Only valid on regional tasks.
    public GridTaskBuilder freeformDestinations (FreeFormPointSet pointSet) {
        destinationKeys.clear();
        registerDestination("POINT_SET", pointSet);
        return this;
    }

    /// Use the supplied Grids as the destinations (as many layered opportunity grids as are supplied). Replaces any
    /// previously registered destinations. The grids need not have the extents of the task or of one another; they
    /// pass through the same wrapping logic as in production, which reconciles grids of unequal extents.
    public GridTaskBuilder griddedDestinations (Grid... grids) {
        destinationKeys.clear();
        for (int i = 0; i < grids.length; i++) {
            registerDestination("GRID_" + i, grids[i]);
        }
        return this;
    }

    private void registerDestination (String key, PointSet pointSet) {
        destinationKeys.add(key);
        pointSetCache.put(key, pointSet);
    }

    /// Attach any registered destination PointSets to the given task through the production code path, which loads
    /// them by key from a cache and wraps grids whose extents do not match the task's extents. Tests targeting that
    /// wrapping behavior depend on this method not setting the destinationPointSets field directly.
    private void attachDestinations (AnalysisWorkerTask newTask) {
        if (destinationKeys.isEmpty()) {
            return;
        }
        newTask.destinationPointSetKeys = destinationKeys.toArray(new String[0]);
        newTask.loadAndValidateDestinationPointSets(pointSetCache);
    }

    /// Materialize the accumulated settings as a task of the given concrete type. The mechanism used to perform the
    /// copy is a round trip through JSON, which may appear bizarre. But all the alternatives that allow producing
    /// tasks of both concrete types involve a full builder pattern with many fields and separate methods to copy every
    /// single field to the instances. In practice, we always receive these objects over the wire as JSON with a field
    /// discriminating between the deserialized types, so this JSON-based copy method actually mimics production use.
    /// Round-tripping the prototype task through that representation conviently yields a deep, independent copy.
    /// Settings that do not exist on the requested type are dropped, and the transient destination PointSets (which
    /// are not serialized in production either) are attached afterward through the production loading path.
    private AnalysisWorkerTask buildForType (AnalysisWorkerTask.Type type) {
        try {
            ObjectNode node = JsonUtilities.objectMapper.valueToTree(task);
            node.put("type", type.toString());
            AnalysisWorkerTask copy = JsonUtilities.lenientObjectMapper.treeToValue(node, AnalysisWorkerTask.class);
            attachDestinations(copy);
            return copy;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Could not build task through serialization round trip.", e);
        }
    }

    /// Produce a new RegionalTask from the settings applied to this builder. The result is a deep copy via
    /// serialization, sharing no mutable state with the builder or with previously built tasks. All built tasks do
    /// reference the same registered destination PointSet instances via the cache. The builder remains valid and may
    /// be modified and reused to produce further tasks.
    public RegionalTask buildRegional () {
        return (RegionalTask) buildForType(AnalysisWorkerTask.Type.REGIONAL_ANALYSIS);
    }

    /// Produce a single-point TravelTimeSurfaceTask equivalent of the task this builder would otherwise produce.
    /// Unlike regional tasks, single point tasks take their destination grid extents from the request itself, so any
    /// registered destination Grid whose extents differ from the task extents will be wrapped to match them.
    ///
    /// If the regional and single-point types are ever merged into a single class (which seems like a beneficial
    /// refactor) the serialization round trip can be replaced with a simple clone() followed by setting the type field.
    public TravelTimeSurfaceTask buildSinglePoint () {
        return (TravelTimeSurfaceTask) buildForType(AnalysisWorkerTask.Type.TRAVEL_TIME_SURFACE);
    }

}
