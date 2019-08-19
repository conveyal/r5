package com.conveyal.r5.analyst;

import com.google.common.cache.LoadingCache;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache Web Mercator Grid Pointsets so that they are not recreated and relinked on every regional analysis task.
 * The cache does not have expiration, which is fine, because it exists on the workers which are short-lived.
 * This is a loading cache, that will compute values when they are absent: the values are not explicitly added by the
 * caller.
 *
 * The WebMercatorGridPointSets are very small and fetching one doesn't include linking.
 * We cache these objects because once they are linked,they contain the linkages, and creating the linkages takes a
 * lot of time.
 *
 * Note that this cache will be serialized with the PointSet, but serializing a Guava cache only serializes the
 * cache instance and its settings, not the contents of the cache. We consider this sane behavior.
 * TODO verify the above comment, this doesn't seem to be serialized anywhere and it doesn't contain a Guava cache.
 */
public class WebMercatorGridPointSetCache {

    private Map<GridKey, WebMercatorGridPointSet> cache = new ConcurrentHashMap<>();

    public WebMercatorGridPointSet get (int zoom, int west, int north, int width, int height, WebMercatorGridPointSet base) {
        GridKey key = new GridKey();
        key.zoom = zoom;
        key.west = west;
        key.north = north;
        key.width = width;
        key.height = height;
        key.base = base;

        // this works even in a multithreaded environment; ConcurrentHashMap's contract specifies that this will be called
        // at most once per key
        return cache.computeIfAbsent(key, GridKey::toPointset);
    }

    public WebMercatorGridPointSet get(WebMercatorExtents extents, WebMercatorGridPointSet base) {
        return get(extents.zoom, extents.west, extents.north, extents.width, extents.height, base);
    }

    // TODO make this a subclass of WebMercatorGridExtents
    private static class GridKey {
        public int zoom;
        public int west;
        public int north;
        public int width;
        public int height;
        public WebMercatorGridPointSet base;

        public WebMercatorGridPointSet toPointset () {
            return new WebMercatorGridPointSet(zoom, west, north, width, height, base);
        }

        public int hashCode () {
            return west + north * 31 + width * 63 + height * 99 + (base == null ? 0 : base.hashCode() * 123);
        }

        public boolean equals (Object o) {
            if (o instanceof GridKey) {
                GridKey other = (GridKey) o;
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
