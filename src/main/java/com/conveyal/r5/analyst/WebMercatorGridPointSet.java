package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.Coordinate;
import org.mapdb.Fun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * A linked pointset that represents a web mercator grid laid over the graph.
 */
public class WebMercatorGridPointSet implements PointSet {
    public static final Logger LOG = LoggerFactory.getLogger(WebMercatorGridPointSet.class);

    public static final int DEFAULT_ZOOM = 9;

    /** web mercator zoom level */
    public final int zoom;

    /** westernmost pixel */
    public final long west;

    /** northernmost pixel */
    public final long north;

    /** width */
    public final long width;

    /** height */
    public final long height;

    private int[] edges;
    private int[] distances0_mm;
    private int[] distances1_mm;

    private Map<Fun.Tuple2<StreetLayer, StreetMode>, LinkedPointSet> linkageCache = new HashMap<>();

    public WebMercatorGridPointSet(int zoom, long west, long north, long width, long height) {
        this.zoom = zoom;
        this.west = west;
        this.north = north;
        this.width = width;
        this.height = height;

    }

    public WebMercatorGridPointSet(TransportNetwork transportNetwork) {
        LOG.info("Creating web mercator pointset for transport network with extents {}", transportNetwork.streetLayer.envelope);

        this.zoom = DEFAULT_ZOOM;
        long west = lonToPixel(transportNetwork.streetLayer.envelope.getMinX());
        long east = lonToPixel(transportNetwork.streetLayer.envelope.getMaxX());
        long north = latToPixel(transportNetwork.streetLayer.envelope.getMaxY());
        long south = latToPixel(transportNetwork.streetLayer.envelope.getMinY());

        this.west = west;
        this.north = north;
        this.height = south - north;
        this.width = east - west;
    }

    @Override
    public int featureCount() {
        return (int) (height * width);
    }

    @Override
    public Coordinate getCoordinate(int index) {
        return null;
    }

    /**
     * Creates an object similar to a SampleSet but for the new TransitNetwork representation.
     * Or returns one from the cache if this operation has already been performed.
     * This is a somewhat slow operation involving a lot of geometry calculations. In some sense the point of making
     * LinkedPointSets is to cache the relationship between the FreeFormPointSet and a StreetLayer, so we don't have to
     * repeatedly do a lot of geometry calculations to temporarily connect the points to the streets.
     */
    @Override
    public LinkedPointSet link(StreetLayer streetLayer, StreetMode streetMode) {
        LinkedPointSet linkedPointSet = linkageCache.get(Fun.t2(streetLayer, streetMode));
        if (linkedPointSet == null) {
            linkedPointSet = new LinkedPointSet(this, streetLayer, streetMode);
            linkageCache.put(Fun.t2(streetLayer, streetMode), linkedPointSet);
        }
        return linkedPointSet;
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

    // http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Mathematics

    /** convert longitude to pixel value */
    public long lonToPixel (double lon) {
        // factor of 256 is to get a pixel value not a tile number
        return (long) ((lon + 180) / 360 * Math.pow(2, zoom) * 256);
    }

    /** convert latitude to pixel value */
    public long latToPixel (double lat) {
        //
        double invCos = 1 / Math.cos(Math.toRadians(lat));
        double tan = Math.tan(Math.toRadians(lat));
        double ln = Math.log(tan + invCos);
        return (long) ((1 - ln / Math.PI) * Math.pow(2, zoom - 1) * 256);
    }

    public double pixelToLon (double x) {
        return x / (Math.pow(2, zoom) * 256) * 360 - 180;
    }

    public double pixelToLat (double y) {
        double tile = y / 256d;
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI - tile * Math.PI * 2 / Math.pow(2, zoom))));
    }
}
