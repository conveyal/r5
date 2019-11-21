package com.conveyal.r5.analyst;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache WebMercatorGridPointsets so that they are not recreated and relinked for every regional analysis task.
 * The cache does not have expiration, which is fine, because it exists on the workers which are short-lived.
 * This is a loading cache, that will compute values when they are absent: the values are not explicitly added by
 * the caller.
 *
 * The WebMercatorGridPointSets are very small and fetching one doesn't include linking or building egress tables.
 * We cache these objects because linkages are associated with them, and creating those linkages takes a lot of
 * time. So this  essentially serves to resolve a given WebMercatorExtents (semantically) to a particular
 * persistent, reused WebMercatorGridPointSet instance. This allows reuse of the linkages for that PointSet in
 * subsequent tasks.
 *
 * Alternatively PointSets could have semantic equality, but current design is that just the keys and extents do.
 *
 * This cache is not serialized anywhere. For now it's created fresh when the worker starts up and held in a
 * static field for the life of the worker. It is primed upon receiving the first request.
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

        // This works even in a multithreaded environment; ConcurrentHashMap's contract specifies that this will
        // be called at most once per key. So ConcurrentHashMap could probably replace a lot of our LoadingCaches.
        return cache.computeIfAbsent(key, GridKey::toPointset);
    }

    public WebMercatorGridPointSet get(WebMercatorExtents extents, WebMercatorGridPointSet base) {
        return get(extents.zoom, extents.west, extents.north, extents.width, extents.height, base);
    }

    /**
     * TODO make this GridKey a subclass of WebMercatorGridExtents or compose them.
     */
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
