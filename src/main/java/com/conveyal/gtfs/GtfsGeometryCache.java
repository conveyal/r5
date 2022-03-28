package com.conveyal.gtfs;

import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.common.GeometryUtils;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This class maintains spatial indexes of data from GTFS feeds.
 */
public class GtfsGeometryCache {
    private static final Logger LOG = LoggerFactory.getLogger(GtfsGeometryCache.class);

    private final GTFSCache gtfsCache;

    /** A cache of spatial indexes of the trip pattern shapes, keyed on the BundleScopedFeedId. */
    public final GeometryCache<LineString> patternShapes;

    /** A cache of spatial indexes of the transit stops, keyed on the BundleScopedFeedId. */
    public final GeometryCache<Point> stops;

    /** Constructor taking a GTFSCache component so we can look up feeds by ID as needed. */
    public GtfsGeometryCache (GTFSCache gtfsCache) {
        this.gtfsCache = gtfsCache;
        this.patternShapes = new GeometryCache<>(this::buildShapesIndex);
        this.stops = new GeometryCache<>(this::buildStopsIndex);
    }

    /** CacheLoader implementation making spatial indexes of stop pattern shapes for a single feed. */
    private void buildShapesIndex (String bundleScopedFeedId, STRtree tree) {
        final var startTimeMs = System.currentTimeMillis();
        var feed = gtfsCache.get(bundleScopedFeedId);
        // This is huge, we can instead map from envelopes to tripIds, but re-fetching those trips is slow
        LOG.info("{}: indexing {} patterns", feed.feedId, feed.patterns.size());
        for (Pattern pattern : feed.patterns.values()) {
            var route = feed.routes.get(pattern.route_id);
            var exemplarTripId = pattern.associatedTrips.get(0);
            var wgsGeometry = feed.getTripGeometry(exemplarTripId);
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
            tree.insert(wgsGeometry.getEnvelopeInternal(), wgsGeometry);
        }
        LOG.info("Created vector tile spatial index for patterns in feed {} ({})", bundleScopedFeedId, Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
    }

    /**
     * CacheLoader implementation making spatial indexes of transit stops for a single feed.
     * This is inefficient, TODO specialized spatial index to bin points into mercator tiles (like hashgrid).
     */
    private void buildStopsIndex (String bundleScopedFeedId, STRtree tree) {
        final var startTimeMs = System.currentTimeMillis();
        var feed = gtfsCache.get(bundleScopedFeedId);
        LOG.info("{}: indexing {} stops", feed.feedId, feed.stops.size());
        for (Stop stop : feed.stops.values()) {
            var stopEnvelope = new Envelope(stop.stop_lon, stop.stop_lon, stop.stop_lat, stop.stop_lat);
            var point = GeometryUtils.geometryFactory.createPoint(new Coordinate(stop.stop_lon, stop.stop_lat));

            Map<String, Object> properties = new HashMap<>();
            properties.put("feedId", stop.feed_id);
            properties.put("id", stop.stop_id);
            properties.put("name", stop.stop_name);
            properties.put("lat", stop.stop_lat);
            properties.put("lon", stop.stop_lon);

            point.setUserData(properties);
            tree.insert(stopEnvelope, point);
        }
        LOG.info("Created spatial index for stops in feed {} ({})", bundleScopedFeedId, Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
    }
}