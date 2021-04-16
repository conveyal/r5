package com.conveyal.r5.transit;

import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.util.Tuple2;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.util.BitSet;
import java.util.EnumSet;

/**
 * Stores the patterns and trips relevant for routing based on the transit modes and date in an analysis request.
 * We can't just cache the single most recently used filtered patterns, because a worker might need to simultaneously
 * handle two requests for the same scenario on different dates or with different modes.
 *
 * There are good reasons why this cache is specific to a single TransitLayer (representing one specific scenario).
 * To create FilteredPatterns we need the source TransitLayer object. LoadingCaches must compute values based only on
 * their keys. So a system-wide FilteredPatternCache would either need to recursively look up TransportNetworks in
 * the TransportNetworkCache, or would need to have TransportNetwork or TransitLayer references in its keys. Neither
 * of these seems desirable - the latter would impede garbage collection of evicted TransportNetworks.
 */
public class FilteredPatternCache {

    /**
     * All FilteredPatterns stored in this cache will be derived from this single TransitLayer representing a single
     * scenario, but for different unique combinations of (transitModes, services).
     */
    private final TransitLayer transitLayer;

    private final LoadingCache<Key, FilteredPatterns> cache;

    public FilteredPatternCache (TransitLayer transitLayer) {
        this.transitLayer = transitLayer;
        this.cache = Caffeine.newBuilder().maximumSize(2).build(key -> {
            return new FilteredPatterns(transitLayer, key.a, key.b);
        });
    }

    // TODO replace all keys and tuples with Java 16/17 Records
    private static class Key extends Tuple2<EnumSet<TransitModes>, BitSet> {
        public Key (EnumSet<TransitModes> transitModes, BitSet servicesActive) {
            super(transitModes, servicesActive);
        }
    }

    public FilteredPatterns get (EnumSet<TransitModes> transitModes, BitSet servicesActive) {
        return cache.get(new Key(transitModes, servicesActive));
    }

}
