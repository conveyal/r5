package com.conveyal.r5.analyst;

import com.google.common.cache.LoadingCache;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache Web Mercator Grid Pointsets so that they are not recreated and relinked on every regional analysis request.
 * The cache does not have expiration, which is fine, because it exists on the workers which are short-lived.
 */
public class WebMercatorGridPointSetCache {

    private Map<GridKey, WebMercatorGridPointSet> cache = new ConcurrentHashMap<>();

    public WebMercatorGridPointSet get (int zoom, int west, int north, int width, int height) {
        GridKey key = new GridKey();
        key.zoom = zoom;
        key.west = west;
        key.north = north;
        key.width = width;
        key.height = height;

        // this works even in a multithreaded environment; ConcurrentHashMap's contract specifies that this will be called
        // at most once per key
        return cache.computeIfAbsent(key, GridKey::toPointset);
    }

    public WebMercatorGridPointSet get(Grid grid) {
        return get(grid.zoom, grid.west, grid.north, grid.width, grid.height);
    }

    private static class GridKey {
        public int zoom;
        public int west;
        public int north;
        public int width;
        public int height;

        public WebMercatorGridPointSet toPointset () {
            return new WebMercatorGridPointSet(zoom, west, north, width, height);
        }

        public int hashCode () {
            return west + north * 31 + width * 63 + height + 99;
        }

        public boolean equals (Object o) {
            if (o instanceof GridKey) {
                GridKey other = (GridKey) o;
                return zoom == other.zoom &&
                        west == other.west &&
                        north == other.north &&
                        width == other.width &&
                        height == other.height;
            } else {
                return false;
            }
        }
    }

}
