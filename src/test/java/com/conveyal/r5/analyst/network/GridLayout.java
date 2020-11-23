package com.conveyal.r5.analyst.network;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.common.SphericalDistanceLibrary;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.transit.TransportNetwork;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Create a gridded transport system with very regular spacing of roads and transit stops,
 * allowing predictable travel times.
 * Fields are integers to emphasize the use of round numbers.
 */
public class GridLayout {

    /** The minimum latitude and longitude from which the grid is grown. */
    protected final Coordinate originPoint;

    protected final int widthAndHeightInBlocks;

    /** Spacing between streets in meters. */
    private final int streetGridSpacingMeters = 200;

    /**
     * Rather than specifying a speed, which would end up producing fractional travel times or requiring
     * fractional speeds to produce simple integral travel times, we just specify how long a transit vehicle takes
     * to move down one block of the grid in seconds.
     * As a reference, 25 kph is about 7 mps, or about 28 seconds to traverse a 200 meter block.
     */
    public final int transitBlockTraversalTimeSeconds = 30;

    /** The length of time that transit vehicles wait at each stop */
    public final int transitDwellSeconds = 0;

    protected final List<GridRoute> routes = new ArrayList<>();

    public GridLayout (Coordinate originPoint, int widthAndHeightInBlocks) {
        this.originPoint = originPoint;
        this.widthAndHeightInBlocks = widthAndHeightInBlocks;
    }

    public final double getIntersectionLat (int y) {
        int metersOffset = streetGridSpacingMeters * y;
        return originPoint.y + SphericalDistanceLibrary.metersToDegreesLatitude(metersOffset);
    }

    public final double getIntersectionLon (int x, double lat) {
        int metersOffset = streetGridSpacingMeters * x;
        return originPoint.x + SphericalDistanceLibrary.metersToDegreesLongitude(metersOffset, lat);
    }

    public Coordinate getIntersectionLatLon (int x, int y) {
        double lat = getIntersectionLat(y);
        double lon = getIntersectionLon(x, lat);
        return new CoordinateXY(lon, lat);
    }

    public TransportNetwork generateNetwork () {
        OSM osm = new GridOsmGenerator(this).generate();
        GTFSFeed gtfs = new GridGtfsGenerator(this).generate();
        TransportNetwork transportNetwork = TransportNetwork.fromInputs(new TNBuilderConfig(), osm, Stream.of(gtfs));
        return null;
    }

    public void addHorizontalRoute (int row, int headwayMinutes) {
        this.routes.add(GridRoute.newHorizontalRoute(this, row, headwayMinutes));
    }

    public void addVerticalRoute (int col, int headwayMinutes) {
        this.routes.add(GridRoute.newVerticalRoute(this, col, headwayMinutes));
    }
}
