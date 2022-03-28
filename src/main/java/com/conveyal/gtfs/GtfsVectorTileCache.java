package com.conveyal.gtfs;

import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.common.GeometryUtils;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * This class maintains a spatial index of data inside GTFS feeds and produces vector tiles of it for use as a
 * preview display. It is used by GtfsController, which defines the associated HTTP endpoints.
 *
 * For testing, find tile numbers with https://www.maptiler.com/google-maps-coordinates-tile-bounds-projection/
 * Examine and analyze individual tiles with https://observablehq.com/@henrythasler/mapbox-vector-tile-dissector
 *
 * The tile format specification is at https://github.com/mapbox/vector-tile-spec/tree/master/2.1
 * To summarize:
 * Extension should be .mvt and MIME content type application/vnd.mapbox-vector-tile
 * A Vector Tile represents data based on a square extent within a projection.
 * Vector Tiles may be used to represent data with any projection and tile extent scheme.
 * The reference projection is Web Mercator; the reference tile scheme is Google's.
 * The tile should not contain information about its bounds and projection.
 * The decoder already knows the bounds and projection of a Vector Tile it requested when decoding it.
 * A Vector Tile should contain at least one layer, and a layer should contain at least one feature.
 * Feature coordinates are integers relative to the top left of the tile.
 * The extent of a tile is the number of integer units across its width and height, typically 4096.
 *
 */
public class GtfsVectorTileCache {

    private static final Logger LOG = LoggerFactory.getLogger(GtfsVectorTileCache.class);

    private final GTFSCache gtfsCache;

    /** How long after it was last accessed to keep a GTFS spatial index in memory. */
    private static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);

    /** The maximum number of feeds for which we keep GTFS spatial indexes in memory at once. */
    private static final int MAX_SPATIAL_INDEXES = 4;

    /** A cache of spatial indexes of the trip pattern shapes, keyed on the BundleScopedFeedId. */
    private final LoadingCache<String, STRtree> shapeIndexCache = createCache(this::buildShapesIndex);

    /** A cache of spatial indexes of the transit stops, keyed on the BundleScopedFeedId. */
    private final LoadingCache<String, STRtree> stopIndexCache = createCache(this::buildStopsIndex);

    private LoadingCache<String, STRtree> createCache (CacheLoader<String, STRtree> loader) {
        return Caffeine.newBuilder()
                .maximumSize(MAX_SPATIAL_INDEXES)
                .expireAfterAccess(EXPIRE_AFTER_ACCESS)
                .removalListener(this::logCacheEviction)
                .build(loader);
    }

    /** Constructor taking a GTFSCache component so we can look up feeds by ID as needed. */
    public GtfsVectorTileCache (GTFSCache gtfsCache) {
        this.gtfsCache = gtfsCache;
    }

    /** CacheLoader implementation making spatial indexes of stop pattern shapes for a single feed. */
    private STRtree buildShapesIndex (String bundleScopedId) {
        final long startTimeMs = System.currentTimeMillis();
        GTFSFeed feed = gtfsCache.get(bundleScopedId);
        STRtree shapesIndex = new STRtree();
        // This is huge, we can instead map from envelopes to tripIds, but re-fetching those trips is slow
        LOG.info("{}: indexing {} patterns", bundleScopedId, feed.patterns.size());
        for (Pattern pattern : feed.patterns.values()) {
            Route route = feed.routes.get(pattern.route_id);
            String exemplarTripId = pattern.associatedTrips.get(0);
            LineString wgsGeometry = feed.getTripGeometry(exemplarTripId);
            if (wgsGeometry == null) {
                // Not sure why some of these are null.
                continue;
            }
            Map<String, Object> userData = new HashMap<>();
            userData.put("id", pattern.pattern_id);
            userData.put("name", pattern.name);
            userData.put("routeId", route.route_id);
            userData.put("routeName", route.route_long_name);
            userData.put("routeColor", Objects.requireNonNullElse(route.route_color, "000000"));
            userData.put("routeType", route.route_type);
            wgsGeometry.setUserData(userData);
            shapesIndex.insert(wgsGeometry.getEnvelopeInternal(), wgsGeometry);
        }
        shapesIndex.build();
        LOG.info("Created vector tile spatial index for patterns in feed {} ({} ms)", bundleScopedId, System.currentTimeMillis() - startTimeMs);
        return shapesIndex;
    }

    /** CacheLoader implementation making spatial indexes of transit stops for a single feed. */
    private STRtree buildStopsIndex (String bundleScopedId) {
        final long startTimeMs = System.currentTimeMillis();
        GTFSFeed feed = gtfsCache.get(bundleScopedId);
        STRtree stopsIndex = new STRtree();
        LOG.info("{}: indexing {} stops", bundleScopedId, feed.stops.size());
        for (Stop stop : feed.stops.values()) {
            // This is inefficient, TODO specialized spatial index to bin points into mercator tiles (like hashgrid).
            Envelope stopEnvelope = new Envelope(stop.stop_lon, stop.stop_lon, stop.stop_lat, stop.stop_lat);
            Point point =  GeometryUtils.geometryFactory.createPoint(new Coordinate(stop.stop_lon, stop.stop_lat));

            Map<String, Object> properties = new HashMap<>();
            properties.put("feedId", stop.feed_id);
            properties.put("id", stop.stop_id);
            properties.put("name", stop.stop_name);
            properties.put("lat", stop.stop_lat);
            properties.put("lon", stop.stop_lon);

            point.setUserData(properties);
            stopsIndex.insert(stopEnvelope, point);
        }
        stopsIndex.build();
        LOG.info("Creating vector tile spatial index for stops in feed {} ({} ms)", bundleScopedId, System.currentTimeMillis() - startTimeMs);
        return stopsIndex;
    }

    /** RemovalListener triggered when a spatial index is evicted from the cache. */
    private void logCacheEviction (String feedId, STRtree value, RemovalCause cause) {
        LOG.info("Vector tile spatial index removed. Feed {}, cause {}.", feedId, cause);
    }

    public List<LineString> getPatternsInEnvelope(String bundleScopedFeedId, Envelope envelope) {
        STRtree shapesIndex = shapeIndexCache.get(bundleScopedFeedId);
        return shapesIndex.query(envelope);
    }

    public List<Point> getStopsInEnvelope(String bundleScopedFeedId, Envelope envelope) {
        STRtree stopsIndex = stopIndexCache.get(bundleScopedFeedId);
        return stopsIndex.query(envelope);
    }
}