package com.conveyal.r5.analyst.network;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.PathResult;
import com.conveyal.r5.analyst.cluster.TimeGridWriter;
import com.conveyal.r5.common.SphericalDistanceLibrary;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransportNetwork;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Envelope;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.conveyal.r5.analyst.WebMercatorGridPointSet.DEFAULT_ZOOM;

/**
 * This is used in testing, to represent and create gridded transport systems with very regular spacing of roads and
 * transit stops, yielding highly predictable travel times that can be tested against actual output from the router.
 *
 * A square grid of points is established to the north and east of a specified origin point, and roads are constructed
 * running horizontally and vertically through the points. Each grid point is then a road intersection. These grid
 * points are assigned 2D integer coordinates, with higher-numbered grid points corresponding to higher latitudes and
 * longitudes. That is to say, grid y coordinates increase from south to north and x coordinates from west to east.
 *
 * Horizontal and vertical transit lines can be added running along the streets at regular headways. The speed of these
 * lines is such that they traverse each block in a round number of seconds.
 */
public class GridLayout {

    /** The minimum latitude and longitude from which the grid is grown. */
    public final Coordinate originPoint;

    /**
     * The number of blocks across the grid in both the vertical and horizontal directions.
     * The number of points and roads in each direction will be one greater than this number (fencepost problem).
     */
    public final int widthAndHeightInBlocks;

    // TODO not yet implemented: position intersections exactly on web Mercator sample points to eliminate walking
    public final boolean alignIntersectionsToMercatorGrid = true;

    /** Spacing between streets in meters. Type is integer to emphasize the use of simple round numbers. */
    public final int streetGridSpacingMeters = 200;

    /**
     * Rather than specifying a speed, which would end up producing fractional travel times or requiring
     * fractional speeds to produce simple integral travel times, we just specify how long a transit vehicle takes
     * to move down one block of the grid in seconds.
     * As a reference, 25 kph is about 7 mps, or about 28 seconds to traverse a 200 meter block.
     * For two stops one block apart, on any given trip the difference between the departure times at those two stops
     * is always equal to this block traversal time, even when a dwell time is specified.
     */
    public final int transitBlockTraversalTimeSeconds = 30;

    /**
     * The walk speed when routing will be derived from this block traversal time to ensure predictable times.
     * For example traversing a 200 meter block at 1.3 meters per second would take 153.8 seconds.
     * Rounding this down to 120 seconds gives an even two minutes, implying a speed of 1.666 m/sec.
     * Setting this to 100 or 200 will give an integral speed in meters per second, which could also be advantageous.
     * It may also be desirable to make walking very fast, so it becomes a negligible part of end to end travel time.
     */
    public final int walkBlockTraversalTimeSeconds = 200;

    /**
     * The length of time that transit vehicles wait at each stop. This is taken out of the block traversal time rather
     * than added to it so that travel time is always (nBlocks * transitBlockTraversalTimeSeconds) - transitDwellSeconds.
     */
    public final int transitDwellSeconds = 0;

    /** The internal list of transit routes that have been added to this gridded transportation system. */
    protected final List<GridRoute> routes = new ArrayList<>();

    private int nextIntegerId = 0;

    /**
     * Create a square grid of streets with the with the default spacing, extending east and north of the origin point.
     */
    public GridLayout (Coordinate originPoint, int widthAndHeightInBlocks) {
        this.originPoint = originPoint;
        this.widthAndHeightInBlocks = widthAndHeightInBlocks;
    }

    public final double getIntersectionLat (int y) {
        int metersOffset = streetGridSpacingMeters * y;
        return originPoint.y + SphericalDistanceLibrary.metersToDegreesLatitude(metersOffset);
    }

    /**
     * It might seem simpler to just determine a single latitude and longitude step value for the entire grid. However
     * we want to ensure that the walk times between intersections are extremely uniform across the whole grid, so we
     * have a different longitude step value at each latitude.
     */
    public final double getIntersectionLon (int x, double lat) {
        int metersOffset = streetGridSpacingMeters * x;
        return originPoint.x + SphericalDistanceLibrary.metersToDegreesLongitude(metersOffset, lat);
    }

    /** Get the latitude and longitude of the given grid point (intersection) in this grid. */
    public Coordinate getIntersectionLatLon (int x, int y) {
        double lat = getIntersectionLat(y);
        double lon = getIntersectionLon(x, lat);
        return new CoordinateXY(lon, lat);
    }

    /** Once a GridLayout is completely set up, calling this method will produce the corresponding TransportNetwork. */
    public TransportNetwork generateNetwork () {
        OSM osm = new GridOsmGenerator(this).generate();
        GTFSFeed gtfs = new GridGtfsGenerator(this).generate();
        TransportNetwork network = TransportNetwork.fromInputs(new TNBuilderConfig(), osm, Stream.of(gtfs));
        // The usual analysis code path always applies a scenario, even an empty one to baseline cases.
        // We are not doing that here.
        // The steps below are taken when the TNCache loads or builds a network, but not in the network build methods.
        // Presumably this is to save time and space when we make a network not used in analysis. Should we change that?
        network.transitLayer.buildDistanceTables(null);
        network.rebuildLinkedGridPointSet(StreetMode.WALK);
        // Set the ID on the network and its layers to allow caching linkages and analysis results.
        network.scenarioId = UUID.randomUUID().toString();
        return network;
    }

    /**
     * This saves the road and transit data to OSM and GTFS files, primarily for debugging purposes. Networks used in
     * tests are produced directly from the internal MapDB backed OSM and GTFS objects without writing them to files.
     */
    public void exportFiles (String baseName) {
        OSM osm = new GridOsmGenerator(this).generate();
        osm.writeToFile(baseName + ".osm.pbf");
        osm.close();
        GTFSFeed gtfs = new GridGtfsGenerator(this).generate();
        gtfs.toFile(baseName + ".gtfs.zip");
        gtfs.close();
    }

    /** Add an east-west route at the given row of the grid, running at the default speed and the given headway. */
    public void addHorizontalRoute (int row, int headwayMinutes) {
        this.routes.add(GridRoute.newHorizontalRoute(this, row, headwayMinutes));
    }

    /** Add an east-west route at the given row of the grid, running at the default speed. Explicit schedules must be
     set separately via startTimes *  */
    public void addHorizontalRoute (int row) {
        this.routes.add(GridRoute.newHorizontalRoute(this, row, -1));
    }


    /** Add a north-south route at the given column of the grid, running at the default speed and the given headway. */
    public void addVerticalRoute (int col, int headwayMinutes) {
        this.routes.add(GridRoute.newVerticalRoute(this, col, headwayMinutes));
    }

    // TODO builder pattern for direction (row or column methods), headway, frequency etc.
    public void addHorizontalFrequencyRoute (int row, int headwayMinutes) {
        this.routes.add(GridRoute.newHorizontalRoute(this, row, headwayMinutes).pureFrequency());
    }

    public void addVerticalFrequencyRoute (int col, int headwayMinutes) {
        this.routes.add(GridRoute.newVerticalRoute(this, col, headwayMinutes).pureFrequency());
    }

    /** Creates a builder for analysis worker tasks, which represent searches on this grid network. */
    public GridSinglePointTaskBuilder newTaskBuilder() {
        return new GridSinglePointTaskBuilder(this);
    }

    public GridSinglePointTaskBuilder copyTask(AnalysisWorkerTask task) {
        return new GridSinglePointTaskBuilder(this, task);
    }

    /** Get the minimum envelope containing all the points in this grid. */
    public Envelope gridEnvelope () {
        Coordinate farCorner = getIntersectionLatLon(widthAndHeightInBlocks, widthAndHeightInBlocks);
        return new Envelope(originPoint.x, farCorner.x, originPoint.y, farCorner.y);
    }

    /**
     * Create a gridded pointset coextensive with this grid, with the given number of opportunities in each cell.
     * TODO maybe derive this from the network's gridded pointset rather than the GridLayout.
     */
    public Grid makeUniformOpportunityDataset (double density) {
        Grid grid = new Grid(DEFAULT_ZOOM, this.gridEnvelope());
        for (double[] column : grid.grid) {
            Arrays.fill(column, density);
        }
        return grid;
    }

    /**
     * Create a gridded pointset coextensive with this grid, with the given number of opportunities in each cell in the
     * eastern half of the grid.
     */
    public Grid makeRightHalfOpportunityDataset (double density) {
        Grid grid = new Grid(DEFAULT_ZOOM, this.gridEnvelope());
        for (int c = grid.grid.length / 2; c < grid.grid.length; c++) {
            Arrays.fill(grid.grid[c], density);
        }
        return grid;
    }

    public int pointIndex(AnalysisWorkerTask task, int x, int y) {
        Coordinate destLatLon = this.getIntersectionLatLon(x, y);
        // Here is a bit of awkwardness where WebMercatorGridPointSet and Grid both extend PointSet, but don't share
        // their grid referencing code, so one would have to be converted to the other to get the point index.
        return new WebMercatorGridPointSet(task.getWebMercatorExtents()).getPointIndexContaining(destLatLon);
    }

    public String nextIntegerId() {
        return Integer.toString(nextIntegerId++);
    }
}
