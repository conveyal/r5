package com.conveyal.r5.analyst;

import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * A pointset that represents a grid of pixels from the web mercator projection.
 * This PointSet subclass does not yet support opportunity counts.
 * TODO merge this class with Grid, which does have opportunity counts.
 */
public class WebMercatorGridPointSet extends PointSet implements Serializable {

    public static final Logger LOG = LoggerFactory.getLogger(WebMercatorGridPointSet.class);

    public static final int DEFAULT_ZOOM = 9;

    /** web mercator zoom level */
    public final int zoom;

    /** westernmost pixel  */
    public final int west;

    /** northernmost pixel */
    public final int north;

    /** The number of pixels across this grid in the east-west direction. */
    public final int width;

    /** The number of pixels from top to bottom of this grid in the north-south direction. */
    public final int height;

    /** Base pointset; linkages will be shared with this pointset */
    public final WebMercatorGridPointSet basePointSet;

    /**
     * Create a new WebMercatorGridPointSet.
     *
     * @oaram basePointSet the super-grid pointset from which linkages will be copied or shared, or null if no
     *        such grid exists.
     */
    public WebMercatorGridPointSet(int zoom, int west, int north, int width, int height, WebMercatorGridPointSet basePointSet) {
        this.zoom = zoom;
        this.west = west;
        this.north = north;
        this.width = width;
        this.height = height;
        this.basePointSet = basePointSet;
    }

    /**
     * Constructs a grid point set that covers the entire extent of the supplied transport network's street network.
     * This usually serves as the base supergrid pointset for other smaller grids in the same region.
     */
    public WebMercatorGridPointSet (TransportNetwork transportNetwork) {
        this(transportNetwork.streetLayer.envelope);
    }

    /**
     * TODO specific data types for Web Mercator and WGS84 floating point envelopes
     * @param wgsEnvelope an envelope in floating-point WGS84 degrees
     */
    public WebMercatorGridPointSet (Envelope wgsEnvelope) {
        LOG.info("Creating WebMercatorGridPointSet with WGS84 extents {}", wgsEnvelope);
        this.zoom = DEFAULT_ZOOM;
        int west = lonToPixel(wgsEnvelope.getMinX());
        int east = lonToPixel(wgsEnvelope.getMaxX());
        int north = latToPixel(wgsEnvelope.getMaxY());
        int south = latToPixel(wgsEnvelope.getMinY());
        this.west = west;
        this.north = north;
        this.height = south - north;
        this.width = east - west;
        this.basePointSet = null;
    }

    /** The resulting PointSet will not have a null basePointSet, so should generally not be used for linking. */
    public WebMercatorGridPointSet (WebMercatorExtents extents) {
        this(extents.zoom, extents.west, extents.north, extents.width, extents.height, null);
    }

    @Override
    public int featureCount() {
        return height * width;
    }

    @Override
    public double sumTotalOpportunities () {
        // For now we are counting each point as 1 opportunity because this class does not have opportunity counts.
        return featureCount();
    }

    @Override
    public double getLat(int i) {
        long y = i / this.width + this.north;
        return pixelToLat(y);
    }

    @Override
    public double getLon(int i) {
        long x = i % this.width + this.west;
        return pixelToLon(x);
    }

    @Override
    public TIntList getPointsInEnvelope (Envelope envelopeFixedDegrees) {
        // Convert fixed-degree envelope to floating, then to world-scale web Mercator pixels at this grid's zoom level.
        // This is not very DRY since we do something very similar in the constructor and elsewhere.
        int west = lonToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMinX()));
        int east = lonToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMaxX()));
        int north = latToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMaxY()));
        int south = latToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMinY()));
        // Make the envelope's pixel values relative to the edges of this WebMercatorGridPointSet, rather than
        // absolute world-scale coordinates at this zoom level.
        west -= this.west;
        east -= this.west;
        north -= this.north;
        south -= this.north;
        TIntList pointsInEnvelope = new TIntArrayList();
        // Pixels are truncated toward zero, and coords increase toward East and South in web Mercator, so <= south/east.
        for (int y = north; y <= south; y++) {
            if (y < 0 || y >= this.height) continue;
            for (int x = west; x <= east; x++) {
                if (x < 0 || x >= this.width) continue;
                // Calculate the 1D (flattened) index into this pointset for the grid cell at (x,y).
                int pointIndex = y * this.width + x;
                pointsInEnvelope.add(pointIndex);
            }
        }
        return pointsInEnvelope;
    }

    // http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Mathematics

    /** Convert longitude to pixel value. */
    public int lonToPixel (double lon) {
        // factor of 256 is to get a pixel value not a tile number
        return (int) ((lon + 180) / 360 * Math.pow(2, zoom) * 256);
    }

    /**
     * Convert latitude to pixel value.
     * This could be static if zoom level was a parameter. And/or could be moved to WebMercatorExtents.
     */
    public int latToPixel (double lat) {
        double invCos = 1 / Math.cos(Math.toRadians(lat));
        double tan = Math.tan(Math.toRadians(lat));
        double ln = Math.log(tan + invCos);
        return (int) ((1 - ln / Math.PI) * Math.pow(2, zoom - 1) * 256);
    }

    public double pixelToLon (double x) {
        return x / (Math.pow(2, zoom) * 256) * 360 - 180;
    }

    // TODO add Javadoc - these are absolute pixels right? They don't seem to be relative to the PointSet edge.
    public double pixelToLat (double y) {
        double tile = y / 256d;
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI - tile * Math.PI * 2 / Math.pow(2, zoom))));
    }

    @Override
    public double getOpportunityCount (int i) {
        // FIXME just counting the points for now, return counts
        return 1D;
    }

    //@Override
    // TODO add this to the PointSet interface
    public String getPointId (int i) {
        int y = i / this.width;
        int x = i % this.width;
        return x + "," + y;
    }

    @Override
    public String toString () {
        return "WebMercatorGridPointSet{" + "zoom=" + zoom + ", west=" + west + ", north=" + north + ", width=" + width + ", height=" + height + ", basePointSet=" + basePointSet + '}';
    }

    @Override
    public Envelope getWgsEnvelope () {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebMercatorExtents getWebMercatorExtents () {
        return new WebMercatorExtents(west, north, width, height, zoom);
    }

    public int indexFromWgsCoordinates(double lon, double lat, int zoom) {
        checkArgument(zoom == this.zoom, "Zoom levels must be identical.");
        int x = lonToPixel(lon) - west;
        int y = latToPixel(lat) - north;
        return y * width + x;
    }

}
