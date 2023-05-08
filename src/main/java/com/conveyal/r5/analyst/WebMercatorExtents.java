package com.conveyal.r5.analyst;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.util.Arrays;

import static com.conveyal.r5.analyst.Grid.latToPixel;
import static com.conveyal.r5.analyst.Grid.lonToPixel;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Really we should have a field pointing to an instance of this in tasks, grids, etc. to factor out all the common
 * fields. Equals and hashcode are semantic, for use as or within hashtable keys.
 *
 * TODO may want to distinguish between WebMercatorExtents, WebMercatorGrid (adds lat/lon conversion functions),
 *      and OpportunityGrid (AKA Grid) which adds opportunity counts. These can compose, not necessarily subclass.
 *      Of course they could all be one class, with the opportunity grid nulled out when there is no density.
 */
public class WebMercatorExtents {

    private static final int MIN_ZOOM = 9;
    private static final int MAX_ZOOM = 12;
    private static final int MAX_GRID_CELLS = 5_000_000;

    /** The pixel number of the westernmost pixel (smallest x value). */
    public final int west;

    /**
     * The pixel number of the northernmost pixel (smallest y value in web Mercator, because y increases from north to
     * south in web Mercator).
     */
    public final int north;

    /** Width in web Mercator pixels */
    public final int width;

    /** Height in web Mercator pixels */
    public final int height;

    /** Web mercator zoom level. */
    public final int zoom;

    /**
     * All factory methods or constructors for WebMercatorExtents should eventually call this constructor,
     * as it will enforce size constraints that prevent straining or stalling the system.
     */
    public WebMercatorExtents (int west, int north, int width, int height, int zoom) {
        this.west = west;
        this.north = north;
        this.width = width;
        this.height = height;
        this.zoom = zoom;
        checkGridSize();
    }

    /**
     * Return the analysis extents embedded in the task object itself.
     * Sometimes these imply gridded origin points (non-Taui regional tasks without freeform origins specified) and
     * sometimes these imply gridded destination points (single point tasks and Taui regional tasks).
     */
    public static WebMercatorExtents forTask (AnalysisWorkerTask task) {
        return new WebMercatorExtents(task.west, task.north, task.width, task.height, task.zoom);
    }

    /**
     * If pointSets are all gridded, return the minimum bounding WebMercatorExtents containing them all.
     * Otherwise return null (this null is a hack explained below and should eventually be made more consistent).
     */
    public static WebMercatorExtents forPointsets (PointSet[] pointSets) {
        checkNotNull(pointSets);
        checkElementIndex(0, pointSets.length, "You must supply at least one destination PointSet.");
        if (pointSets[0] instanceof Grid || pointSets[0] instanceof GridTransformWrapper) {
            WebMercatorExtents extents = pointSets[0].getWebMercatorExtents();
            for (PointSet pointSet : pointSets) {
                extents = extents.expandToInclude(pointSet.getWebMercatorExtents());
            }
            return extents;
        } else {
            // Temporary way to bypass network preloading while freeform pointset functionality is being
            // developed. For now, the null return value is used in TravelTimeComputer to signal that the worker
            // should use a provided freeform pointset, rather than creating a WebMercatorGridPointSet based on the
            // parameters of the request.
            checkArgument(pointSets.length == 1, "You may only specify one non-gridded PointSet.");
            return null;
        }
    }

    /**
     * Create the minimum-size immutable WebMercatorExtents containing both this one and the other one.
     * Note that WebMercatorExtents fields are immutable, and this method does not modify the instance in place.
     * It creates a new instance. This behavior differs from GeoTools / JTS Envelopes.
     */
    public WebMercatorExtents expandToInclude (WebMercatorExtents other) {
        checkState(this.zoom == other.zoom, "All grids supplied must be at the same zoom level.");
        final int thisEast = this.west + this.width;
        final int otherEast = other.west + other.width;
        final int thisSouth = this.north + other.height;
        final int otherSouth = other.north + other.height;
        final int outWest = Math.min(other.west, this.west);
        final int outEast = Math.max(otherEast, thisEast);
        final int outNorth = Math.min(other.north, this.north);
        final int outSouth = Math.max(otherSouth, thisSouth);
        final int outWidth = outEast - outWest;
        final int outHeight = outSouth - outNorth;
        return new WebMercatorExtents(outWest, outNorth, outWidth, outHeight, this.zoom);
    }

    public static WebMercatorExtents forWgsEnvelope (Envelope wgsEnvelope, int zoom) {
        /*
          The grid extent is computed from the points. If the cell number for the right edge of the grid is rounded
          down, some points could fall outside the grid. `latToPixel` and `lonToPixel` naturally truncate down, which is
          the correct behavior for binning points into cells but means the grid is (almost) always 1 row too
          narrow/short, so we add 1 to the height and width when a grid is created in this manner. The exception is
          when the envelope edge lies exactly on a pixel boundary. For this reason we should probably not produce WGS
          Envelopes that exactly align with pixel edges, but they should instead surround the points at pixel centers.
          Note also that web Mercator coordinates increase from north to south, so minimum latitude is maximum y.
          TODO maybe use this method when constructing Grids. Grid (int zoom, Envelope envelope)
         */
        int north = latToPixel(wgsEnvelope.getMaxY(), zoom);
        int west = lonToPixel(wgsEnvelope.getMinX(), zoom);
        int height = (latToPixel(wgsEnvelope.getMinY(), zoom) - north) + 1; // minimum height is 1
        int width = (lonToPixel(wgsEnvelope.getMaxX(), zoom) - west) + 1; // minimum width is 1
        WebMercatorExtents webMercatorExtents = new WebMercatorExtents(west, north, width, height, zoom);
        return webMercatorExtents;
    }

    @Override
    public boolean equals (Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebMercatorExtents extents = (WebMercatorExtents) o;
        return west == extents.west && north == extents.north && width == extents.width && height == extents.height && zoom == extents.zoom;
    }

    @Override
    public int hashCode () {
        return hashCode(west, north, width, height, zoom);
    }

    private static int hashCode (int... ints) {
        return Arrays.hashCode(ints);
    }


    /**
     * Create a ReferencedEnvelope for these extents in the Spherical Mercator coordinate reference system as defined
     * in the CRS EPSG:3857. Unlike our usual pixel and tile oriented system, this EPSG definition uses units of meters
     * rather than pixels, and uses both positive and negative coordinates. Specifying this CRS in meters as defined
     * in EPSG ensures that exported files are understood by external GIS tools.
     */
    public ReferencedEnvelope getMercatorEnvelopeMeters () {
        Coordinate northwest = mercatorPixelToMeters(west, north, zoom);
        Coordinate southeast = mercatorPixelToMeters(west + width, north + height, zoom);
        Envelope mercatorEnvelope = new Envelope(northwest, southeast);
        try {
            // Get Spherical Mercator pseudo-projection CRS
            CoordinateReferenceSystem webMercator = CRS.decode("EPSG:3857");
            ReferencedEnvelope env = new ReferencedEnvelope(mercatorEnvelope, webMercator);
            return env;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * This static utility method returns a Coordinate in units of meters (as defined in the CRS EPSG:3857) for the
     * given absolute (world) x and y pixel numbers at the given zoom level.
     *
     * Typically we express spherical mercator coordinates as pixels at some non-negative integer zoom level. At zoom
     * level zero, these correspond to the pixels in a single tile 256 pixels square, i.e. 2^8 pixels on a side. At each
     * successive zoom level, the tile is split in half in each dimension, defining four sub-tiles. Thus coordinates
     * at a different zoom level can be obtained by right or left bit-shifting by the difference in zoom levels, and
     * the tile number is simply the most significant bits of the coordinate with the 8 intra-tile bits shifted off.
     *
     * The projection cuts off at just over 85 degrees latitude, such that the single zoom level zero tile is square.
     * Pixel numbers follow computer graphics conventions, with y increasing toward the south (down) and x to the east.
     * At zoom zero, integer x and y coordinates each fit in one unsigned byte, and resolution can be doubled 23 times
     * without overflowing a standard 32 bit signed integer (i.e. 31 non-sign bits minus the 8 used at zoom level zero).
     * Although this allows for 23 zoom levels, in practice we rarely see more than 18.
     *
     * However, for some reason EPSG specifies units of meters rather than pixels, so our pixel-oriented coordinates
     * must be scaled to match the circumference of the earth. Furthermore EPSG places the origin at latitude and
     * longitude (0,0) using positive and negative coordinates, rather than using nonnegative coordinates and an origin
     * in the northwest corner, so we have to translate all coordinates halfway around the world.
     *
     * The meter was originally defined as 1/10k of the distance from the equator to the pole (with a bit of error), so
     * the earth's circumference is roughly four times this value, at about 40k kilometers. The distance from the origin
     * halfway around the world to +180 should be roughly 20k kilometers.
     *
     * We can determine the value spherical Mercator uses for the width of the world by transforming WGS84 coordinates:
     * $ gdaltransform -s_srs epsg:4326 -t_srs epsg:3857
     * 180, 0
     * 20037508.3427892 -7.08115455161362e-10 0
     *
     * You can't do 180, 90 because there would be a singularity at 90 degrees and this projection is cut off above a
     * certain latitude to make the world square. But you can do the reverse projection to find the cutoff latitude:
     * $ gdaltransform -s_srs epsg:3857 -t_srs epsg:4326
     * 20037508.342789, 20037508.342789
     * 179.999999999998 85.0511287798064 0
     *
     * @param xPixel absolute (world) x pixel number at the specified zoom level, increasing eastward
     * @param yPixel absolute (world) y pixel number at the specified zoom level, increasing southward
     * @return a Coordinate in units of meters, as defined in the CRS EPSG:3857
     */
    public static Coordinate mercatorPixelToMeters (double xPixel, double yPixel, int zoom) {
        double worldWidthPixels = Math.pow(2, zoom) * 256D;
        // Top left is min x and y because y increases toward the south in web Mercator. Bottom right is max x and y.
        // The 0.5 terms below shift the origin from the upper left of the world (pixels) to WGS84 (0,0) (for meters).
        final double worldWidthMeters = 20037508.342789244 * 2;
        double xMeters = ((xPixel / worldWidthPixels) - 0.5) * worldWidthMeters;
        double yMeters = (0.5 - (yPixel / worldWidthPixels)) * worldWidthMeters; // flip y axis
        return new Coordinate(xMeters, yMeters);
    }

    /**
     * Users may create very large grids in various ways. For example, by setting large custom analysis bounds or by
     * uploading spatial data sets with very large extents. This method checks some limits on the zoom level and total
     * number of cells to avoid straining or stalling the system.
     *
     * The fields of WebMercatorExtents are immutable and are not typically deserialized from incoming HTTP API
     * requests. As all instances are created through a constructor, so we can perform this check every time a grid is
     * created. If we do eventually deserialize these from HTTP API requests, we'll have to call the check explicitly.
     * The Grid class internally uses a WebMercatorExtents field, so dimensions are also certain to be checked while
     * constructing a Grid.
     *
     * An analysis destination grid might become problematic at a smaller size than an opportunity data grid. But until
     * we have a reason to distinguish different situations, MAX_GRID_CELLS is defined as a constant in this class.
     * If needed, we can make the max size a method parameter and pass in different limits in different contexts.
     */
    public void checkGridSize () {
        if (this.zoom < MIN_ZOOM || this.zoom > MAX_ZOOM) {
            throw AnalysisServerException.badRequest(String.format(
                    "Requested zoom (%s) is outside valid range (%s - %s)", this.zoom, MIN_ZOOM, MAX_ZOOM
            ));
        }
        if (this.height * this.width > MAX_GRID_CELLS) {
            throw AnalysisServerException.badRequest(String.format(
                    "Requested number of destinations (%s) exceeds limit (%s). " +
                            "Set smaller custom geographic bounds or a lower zoom level.",
                    this.height * this.width, MAX_GRID_CELLS
            ));
        }
    }

}
