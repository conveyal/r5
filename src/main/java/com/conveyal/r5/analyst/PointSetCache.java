package com.conveyal.r5.analyst;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * A cache for a geographic PointSet, including grid-based and custom pointset-based sets.
 * This allows pointsets to not be recreated and relinked on every analysis task.
 * The cache does not have expiration.
 */
public class PointSetCache {
    public Map<PointSetCacheKey, PointSet> cache = new ConcurrentHashMap<>();

    public PointSet get(PointSet set) {
        PointSetWithIds ptSet = (PointSetWithIds) set;
        PointSetCacheKey key = new PointSetCacheKey();
        key.base = ptSet;
        return cache.computeIfAbsent(key, (PointSetCacheKey pKey) -> {
            return (PointSetWithIds) pKey.base;
        });
    }

    public PointSet get(int zoom, int west, int north, int width, int height, PointSet set) {
        if (set.getClass() == WebMercatorGridPointSet.class) {
            WebMercatorGridPointSet gridPointSet = (WebMercatorGridPointSet) set;
            PointSetCacheKey key = new PointSetCacheKey();
            key.zoom = zoom;
            key.west = gridPointSet.west;
            key.north = gridPointSet.north;
            key.width = gridPointSet.width;
            key.height = gridPointSet.height;
            key.base = gridPointSet.base;
            return cache.computeIfAbsent(key, (PointSetCacheKey pKey) -> {
                return new WebMercatorGridPointSet(
                    pKey.zoom,
                    pKey.west,
                    pKey.north,
                    pKey.width,
                    pKey.height,
                    (WebMercatorGridPointSet) pKey.base
                );
            });
        } else {
            return get(set);
        }
    }

    private static class PointSetCacheKey {
        public int zoom;
        public int west;
        public int north;
        public int width;
        public int height;
        public PointSet base;

        public int hashCode () {
            return west + north * 31 + width * 63 + height * 99 +
                (base == null ? 0 : base.hashCode() * 123);
        }

        public boolean equals (Object o) {
            if (o instanceof PointSetCacheKey) {
                PointSetCacheKey other = (PointSetCacheKey) o;
                return zoom == other.zoom &&
                        west == other.west &&
                        north == other.north &&
                        width == other.width &&
                        height == other.height &&
                        base == other.base;
            } else {
                return false;
            }
        }
    }
}
