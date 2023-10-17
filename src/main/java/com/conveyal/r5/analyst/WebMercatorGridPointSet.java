package com.conveyal.r5.analyst;

import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

import static com.conveyal.r5.analyst.Grid.latToPixel;
import static com.conveyal.r5.analyst.Grid.lonToPixel;
import static com.conveyal.r5.analyst.Grid.pixelToCenterLat;
import static com.conveyal.r5.analyst.Grid.pixelToCenterLon;
import static com.conveyal.r5.analyst.Grid.pixelToLat;
import static com.conveyal.r5.analyst.Grid.pixelToLon;
import static com.conveyal.r5.common.GeometryUtils.checkWgsEnvelopeSize;
import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * A pointset that represents a grid of pixels from the web mercator projection.
 * This PointSet subclass does not yet support opportunity counts.
 * TODO merge this class with Grid, which does have opportunity counts.
 */
public class WebMercatorGridPointSet extends PointSet implements Serializable {

    public static final Logger LOG = LoggerFactory.getLogger(WebMercatorGridPointSet.class);

    /**
     * Default Web Mercator zoom level for grids (origin/destination layers, aggregation area masks, etc.).
     * Level 10 is probably ideal but will quadruple calculation relative to 9.
     */
    public static final int DEFAULT_ZOOM = 9;

    public static final int MIN_ZOOM = 9;

    public static final int MAX_ZOOM = 12;

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
        checkWgsEnvelopeSize(wgsEnvelope, "grid point set");
        this.zoom = DEFAULT_ZOOM;
        int west = lonToPixel(wgsEnvelope.getMinX(), zoom);
        int east = lonToPixel(wgsEnvelope.getMaxX(), zoom);
        int north = latToPixel(wgsEnvelope.getMaxY(), zoom);
        int south = latToPixel(wgsEnvelope.getMinY(), zoom);
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
        final int y = i / this.width + this.north;
        return pixelToCenterLat(y, zoom);
    }

    @Override
    public double getLon(int i) {
        final int x = i % this.width + this.west;
        return pixelToCenterLon(x, zoom);
    }

    @Override
    public TIntList getPointsInEnvelope (Envelope envelopeFixedDegrees) {
        // Convert fixed-degree envelope to floating, then to world-scale web Mercator pixels at this grid's zoom level.
        // This is not very DRY since we do something very similar in the constructor and elsewhere.
        int west = lonToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMinX()), zoom);
        int east = lonToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMaxX()), zoom);
        int north = latToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMaxY()), zoom);
        int south = latToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMinY()), zoom);
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

    public int getPointIndexContaining (Coordinate latLon) {
        return  getPointIndexContaining(latLon.x, latLon.y);
    }

    public int getPointIndexContaining (double lon, double lat) {
        int x = lonToPixel(lon, zoom) - west;
        int y = latToPixel(lat, zoom) - north;
        return y * width + x;
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

    public static int parseZoom(String zoomString) {
        int zoom = (zoomString == null) ? DEFAULT_ZOOM : Integer.parseInt(zoomString);
        checkArgument(zoom >= MIN_ZOOM && zoom <= MAX_ZOOM);
        return zoom;
    }

}
