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

    public final WebMercatorExtents extents;
    /** Base pointset; linkages will be shared with this pointset */
    public final WebMercatorGridPointSet basePointSet;

    /**
     * Create a new WebMercatorGridPointSet.
     * @oaram basePointSet the super-grid pointset from which linkages will be copied or shared, or null if no
     *        such grid exists.
     */
    public WebMercatorGridPointSet(int zoom, int west, int north, int width, int height, WebMercatorGridPointSet basePointSet) {
        this.extents = new WebMercatorExtents(west, north, width, height, zoom);
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
        // NOTE the width and height calculations were wrong before, use standard ones in WebMercatorExtents
        this.extents = WebMercatorExtents.forWgsEnvelope(wgsEnvelope, WebMercatorExtents.DEFAULT_ZOOM); // USE TRIMMED?
        this.basePointSet = null;
    }

    /** The resulting PointSet will not have a null basePointSet, so should generally not be used for linking. */
    public WebMercatorGridPointSet (WebMercatorExtents extents) {
        this(extents.zoom, extents.west, extents.north, extents.width, extents.height, null);
    }

    @Override
    public int featureCount() {
        return extents.height * extents.width;
    }

    @Override
    public double sumTotalOpportunities () {
        // For now we are counting each point as 1 opportunity because this class does not have opportunity counts.
        return featureCount();
    }

    @Override
    public double getLat(int i) {
        long y = i / extents.width + extents.north;
        return pixelToLat(y, extents.zoom);
    }

    @Override
    public double getLon(int i) {
        long x = i % extents.width + extents.west;
        return pixelToLon(x, extents.zoom);
    }

    @Override
    public TIntList getPointsInEnvelope (Envelope envelopeFixedDegrees) {
        // Convert fixed-degree envelope to floating, then to world-scale web Mercator pixels at this grid's zoom level.
        // This is not very DRY since we do something very similar in the constructor and elsewhere.
        // These are the integer pixel numbers containing the envelope, so iteration below must be inclusive of the max.
        final int z = extents.zoom;
        int west = lonToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMinX()), z);
        int east = lonToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMaxX()), z);
        int north = latToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMaxY()), z);
        int south = latToPixel(fixedDegreesToFloating(envelopeFixedDegrees.getMinY()), z);
        // Make the envelope's pixel values relative to the edges of this WebMercatorGridPointSet, rather than
        // absolute world-scale coordinates at this zoom level.
        west -= extents.west;
        east -= extents.west;
        north -= extents.north;
        south -= extents.north;
        TIntList pointsInEnvelope = new TIntArrayList();
        // Pixels are truncated toward zero, and coords increase toward East and South in web Mercator, so <= south/east.
        for (int y = north; y <= south; y++) {
            if (y < 0 || y >= extents.height) continue;
            for (int x = west; x <= east; x++) {
                if (x < 0 || x >= extents.width) continue;
                // Calculate the 1D (flattened) index into this pointset for the grid cell at (x,y).
                int pointIndex = y * extents.width + x;
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
        int y = i / extents.width;
        int x = i % extents.width;
        return x + "," + y;
    }

    public int getPointIndexContaining (Coordinate latLon) {
        return  getPointIndexContaining(latLon.x, latLon.y);
    }

    public int getPointIndexContaining (double lon, double lat) {
        int x = lonToPixel(lon, extents.zoom) - extents.west;
        int y = latToPixel(lat, extents.zoom) - extents.north;
        return y * extents.width + x;
    }

    @Override
    public String toString() {
        return "WebMercatorGridPointSet{" +
                "extents=" + extents +
                ", basePointSet=" + basePointSet +
                '}';
    }

    @Override
    public Envelope getWgsEnvelope () {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebMercatorExtents getWebMercatorExtents () {
        // WebMercatorExtents are immutable so we can return this without making a protective copy.
        return extents;
    }

}
