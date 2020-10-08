package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import org.locationtech.jts.geom.Envelope;

import java.util.Arrays;

import static com.conveyal.r5.analyst.Grid.latToPixel;
import static com.conveyal.r5.analyst.Grid.lonToPixel;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Really we should be embedding one of these in the tasks, grids, etc. to factor out all the common fields.
 * Equals and hashcode are semantic, for use as or within hashtable keys.
 *
 * TODO may want to distinguish between WebMercatorExtents, WebMercatorGrid (adds lat/lon conversion functions),
 *      and OpportunityGrid (AKA Grid) which adds opportunity counts. These can compose, not necessarily subclass.
 *      Of course they could all be one class, with the opportunity grid nulled out when there is no density.
 */
public class WebMercatorExtents {

    public final int west;
    public final int north;
    public final int width;
    public final int height;
    public final int zoom;

    public WebMercatorExtents (int west, int north, int width, int height, int zoom) {
        this.west = west;
        this.north = north;
        this.width = width;
        this.height = height;
        this.zoom = zoom;
    }

    public static WebMercatorExtents forTask (AnalysisWorkerTask task) {
        return new WebMercatorExtents(task.west, task.north, task.width, task.height, task.zoom);
    }

    /** If pointSets are all gridded, return the minimum-sized WebMercatorExtents containing them all. */
    public static WebMercatorExtents forPointsets (PointSet[] pointSets) {
        checkNotNull(pointSets);
        checkElementIndex(0, pointSets.length, "You must supply at least one destination PointSet.");
        if (pointSets[0] instanceof Grid) {
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

    /** Create the minimum-size immutable WebMercatorExtents containing both this one and the other one. */
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

}
