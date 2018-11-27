package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.Coordinate;
import org.mapdb.Fun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A pointset that represents a grid of pixels from the web mercator projection.
 */
public class WebMercatorGridPointSet extends PointSet implements Serializable {

    public static final Logger LOG = LoggerFactory.getLogger(WebMercatorGridPointSet.class);

    public static final int DEFAULT_ZOOM = 9;

    /** web mercator zoom level */
    public final int zoom;

    /** westernmost pixel */
    public final int west;

    /** northernmost pixel */
    public final int north;

    /** width */
    public final int width;

    /** height */
    public final int height;

    /** Base pointset; linkages will be shared with this pointset */
    public final WebMercatorGridPointSet base;

    /** Create a new WebMercatorGridPointSet with no base pointset */
    public WebMercatorGridPointSet(int zoom, int west, int north, int width, int height) {
        this(zoom, west, north, width, height, null);
    }

    /** Create a new WebMercatorGridPointSet with a base pointset from which linkages will be shared */
    public WebMercatorGridPointSet(int zoom, int west, int north, int width, int height, WebMercatorGridPointSet base) {
        this.zoom = zoom;
        this.west = west;
        this.north = north;
        this.width = width;
        this.height = height;
        this.base = base;
        // Copy subsets of linkages already cached in the base linkage into to this point set. A walking linkage is
        // pre-built for the full geographic extent transport network, and linkages for other modes may already exist.
        // We can re-use the data in these linkages instead of re-computing them from scratch for the new extents.
        // LinkedPointSet now handles the case where the new grid is not completely contained by the base grid.
        // Since this generally happens when there are points beyond the transit network's extents, marking the points
        // that are not contained by the base linkage as unlinked (and logging a warning if all points are beyond
        // the network) is sufficient as you will not be able to reach these locations anyhow.
        // TODO don't copy, just have a cache that wraps the base pointset and can read through to the original linkages
        // TODO maybe put all the linkages into the cache (none in the map) when building upon a base
        if (base != null) {
            base.linkageMap.forEach(
                    (key, baseLinkage) -> this.linkageMap.put(key, new LinkedPointSet(baseLinkage, this)));
            base.linkageCache.asMap().forEach(
                    (key, baseLinkage) -> this.linkageCache.put(key, new LinkedPointSet(baseLinkage, this)));
        }
    }

    /**
     * Constructs a grid point set that covers the entire extent of the supplied transport network's street network.
     */
    public WebMercatorGridPointSet(TransportNetwork transportNetwork) {
        LOG.info("Creating web mercator pointset for transport network with extents {}", transportNetwork.streetLayer.envelope);

        this.zoom = DEFAULT_ZOOM;
        int west = lonToPixel(transportNetwork.streetLayer.envelope.getMinX());
        int east = lonToPixel(transportNetwork.streetLayer.envelope.getMaxX());
        int north = latToPixel(transportNetwork.streetLayer.envelope.getMaxY());
        int south = latToPixel(transportNetwork.streetLayer.envelope.getMinY());

        this.west = west;
        this.north = north;
        this.height = south - north;
        this.width = east - west;
        this.base = null;
    }

    @Override
    public int featureCount() {
        return height * width;
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
    public int lonToPixel (double lon) {
        // factor of 256 is to get a pixel value not a tile number
        return (int) ((lon + 180) / 360 * Math.pow(2, zoom) * 256);
    }

    /** convert latitude to pixel value */
    public int latToPixel (double lat) {
        double invCos = 1 / Math.cos(Math.toRadians(lat));
        double tan = Math.tan(Math.toRadians(lat));
        double ln = Math.log(tan + invCos);
        return (int) ((1 - ln / Math.PI) * Math.pow(2, zoom - 1) * 256);
    }

    public double pixelToLon (double x) {
        return x / (Math.pow(2, zoom) * 256) * 360 - 180;
    }

    public double pixelToLat (double y) {
        double tile = y / 256d;
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI - tile * Math.PI * 2 / Math.pow(2, zoom))));
    }

}
