package com.conveyal.gtfs;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Cache STRTree spatial indexes of geometries of a particular type, with each spatial index keyed on a String
 * (typically the bundle-scoped feed ID of the GTFS feed the geometries are drawn from). This is based on a Caffeine
 * LoadingCache so should be thread safe and provide granular per-key locking, which is convenient when serving up
 * lots of simultaneous vector tile requests.
 *
 * This is currently used only for looking up geomertries when producing Mapbox vector map tiles, hence the single
 * set of hard-wired cache eviction parameters. For more general use we'd want another constructor to change them.
 */
public class GeometryCache<T extends Geometry> {
    private static final Logger LOG = LoggerFactory.getLogger(GeometryCache.class);

    /** How long after it was last accessed to keep a spatial index in memory. */
    private static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);

    /** The maximum number of spatial indexes to keep in memory at once. */
    private static final int MAX_SPATIAL_INDEXES = 4;

    /** A cache of spatial indexes, usually keyed on a feed ID. */
    private final LoadingCache<String, STRtree> cache;

    public GeometryCache(BiConsumer<String, STRtree> loader) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_SPATIAL_INDEXES)
                .expireAfterAccess(EXPIRE_AFTER_ACCESS)
                .removalListener(this::logCacheEviction)
                .build((key) -> {
                    var tree = new STRtree();
                    loader.accept(key, tree);
                    tree.build();
                    return tree;
                });
    }

    /** RemovalListener triggered when a spatial index is evicted from the cache. */
    private void logCacheEviction (String feedId, STRtree value, RemovalCause cause) {
        LOG.info("Spatial index removed. Feed {}, cause {}.", feedId, cause);
    }

    /**
     * Return a List including all geometries that intersect a given envelope.
     * Note that this overselects (can and will return some geometries outside the envelope).
     * It could make sense to move some of the clipping logic in here if this class remains vector tile specific.
     */
    public List<T> queryEnvelope(String key, Envelope envelope) {
        return cache.get(key).query(envelope);
    }
}