package com.conveyal.r5.analyst.network;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.common.SphericalDistanceLibrary;
import com.conveyal.r5.transit.TransportNetwork;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Envelope;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    /**
     * Get the latitude and longitude of an arbitrary location in this grid. X and Y parameters are in units of blocks
     * from the grid origin. Fractional coordinates yield points along blocks (when the other coordinate is an integer)
     * or off the streets entirely (when both are fractional), for tests of street splitting and off-street linking.
     */
    public Coordinate getPointLatLon (double xBlocks, double yBlocks) {
        double lat = originPoint.y + SphericalDistanceLibrary.metersToDegreesLatitude(streetGridSpacingMeters * yBlocks);
        double lon = originPoint.x + SphericalDistanceLibrary.metersToDegreesLongitude(streetGridSpacingMeters * xBlocks, lat);
        return new CoordinateXY(lon, lat);
    }

    /// Once a GridLayout is completely set up, calling this method will produce the corresponding TransportNetwork.
    /// The production analysis code path always applies a scenario, even for baseline cases where the scenario is
    /// empty. It analyzes only the resulting scenario copy, never a base network object like the one returned here.
    /// When a network is used in analysis, the egress cost tables of its linkages are destructively transposed. This
    /// is intentional: the tables are large and the untransposed form is not needed during analysis, so it is left to
    /// be garbage collected. However, scenario application copies entries from the base network's untransposed tables,
    /// so a network that has been analyzed directly can no longer have scenarios applied to it (see EgressCostTable).
    /// So in sum, scenarios are always applied before analysis, but only ever applied one layer deep to a base network.
    /// Tests that never apply scenarios may analyze the returned network directly. Tests that apply scenarios must
    /// follow the production pattern, analyzing only scenario copies.
    /// The steps below are taken when the TNCache loads or builds a network, but not in the network build methods.
    /// Presumably this is to save time and space when we make a network not used in analysis. Should we change that?
    public TransportNetwork generateNetwork () {
        OSM osm = new GridOsmGenerator(this).generate();
        GTFSFeed gtfs = new GridGtfsGenerator(this).generate();
        // The usual analysis code path always applies a scenario, even an empty one to baseline cases.
        // We are not doing that here.
        return TransportNetwork.build(null, osm, Stream.of(gtfs), true);
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

    /**
     * Add an east-west route at the given row of the grid, running at the default speed and the given headway.
     * This and the other route-adding methods return the created GridRoute so callers can adjust its fields,
     * for example assigning a known route ID to replace the sequentially generated default.
     */
    public GridRoute addHorizontalRoute (int row, int headwayMinutes) {
        GridRoute route = GridRoute.newHorizontalRoute(this, row, headwayMinutes);
        this.routes.add(route);
        return route;
    }

    /** Add an east-west route at the given row of the grid, running at the default speed. Explicit schedules must be
     set separately via startTimes *  */
    public GridRoute addHorizontalRoute (int row) {
        GridRoute route = GridRoute.newHorizontalRoute(this, row, -1);
        this.routes.add(route);
        return route;
    }

    /** Add a north-south route at the given column of the grid, running at the default speed and the given headway. */
    public GridRoute addVerticalRoute (int col, int headwayMinutes) {
        GridRoute route = GridRoute.newVerticalRoute(this, col, headwayMinutes);
        this.routes.add(route);
        return route;
    }

    // TODO builder pattern for direction (row or column methods), headway, frequency etc.
    public GridRoute addHorizontalFrequencyRoute (int row, int headwayMinutes) {
        GridRoute route = GridRoute.newHorizontalRoute(this, row, headwayMinutes).pureFrequency();
        this.routes.add(route);
        return route;
    }

    public GridRoute addVerticalFrequencyRoute (int col, int headwayMinutes) {
        GridRoute route = GridRoute.newVerticalRoute(this, col, headwayMinutes).pureFrequency();
        this.routes.add(route);
        return route;
    }

    /** Creates a builder for analysis worker tasks, which represent searches on this grid network. */
    public GridTaskBuilder newTaskBuilder() {
        return new GridTaskBuilder(this);
    }

    /** Get the minimum envelope containing all the points in this grid. */
    public Envelope gridEnvelope () {
        Coordinate farCorner = getIntersectionLatLon(widthAndHeightInBlocks, widthAndHeightInBlocks);
        return new Envelope(originPoint.x, farCorner.x, originPoint.y, farCorner.y);
    }

    public String nextIntegerId() {
        return Integer.toString(nextIntegerId++);
    }
}
