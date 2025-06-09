package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * Retains linkages between PointSets and the StreetLayers for specific StreetModes, including egress distance tables.
 * LinkageCaches used to be associated with individual PointSets, but now there is a single cache per TransportNetwork.
 * There could instead be one instance per AnalystWorker or per JVM (static), but this would cause the mappings
 * including PointSets, StreetLayers, and Linkages (which hold references to the TransportNetwork) to stick around
 * even when we try to garbage collect a TransportNetwork. In cloud operation, this problem would not necessarily arise
 * in practice since workers are permanently associated with a single base TransportNetwork.
 */
public class LinkageCache {

    private static final Logger LOG = LoggerFactory.getLogger(LinkageCache.class);

    /**
     * Maximum number of street network linkages and associated egress tables to retain in this LinkageCache.
     * This is a crude way of limiting memory consumption, and would ideally be replaced with a WeighingCache.
     * However, the memory consumption of a particular linkage is difficult to quantify, as the bulk of the data
     * is distance tables, and multiple linkages may share a large number of references to reused distance tables.
     * Since every Scenario including the baseline has its own StreetLayer instance, we could for example hold linkages
     * (with associated distance tables) for walk, bike, and car egress for 2 scenarios plus the baseline at once.
     */
    public static int LINKAGE_CACHE_SIZE = 9;

    /**
     * For a particular TransportNetwork, a different linkage is produced for each unique combination of destination
     * points, StreetLayer, and on-street mode of travel (see details of Key). A distinct StreetLayer instance exists
     * for each scenario even when its contents remain unchanged by the scenario, because the StreetLayer references
     * the enclosing TransportNetwork for the scenario.
     * Note that this cache will be serialized with the PointSet, but serializing a Guava cache only serializes the
     * cache instance and its settings, not the contents of the cache. This is the intended behavior.
     */
    protected transient LoadingCache<Key, LinkedPointSet> linkageCache;

    /**
     * This Map augments the LoadingCache with linkages that should never be evicted. The original base linkage for
     * a network (a walk mode linkage for the entire region) should never be evicted. There is a reference to it in
     * the Network instance, so that linkage (and its distance tables) are always using space in memory. So there
     * is zero additional cost to keep them in cache forever.
     */
    protected Map<Key, LinkedPointSet> linkageMap = new HashMap<>();

    /**
     * The logic for lazy-loading linkages into the cache.
     *
     * FIXME clean up these notes on sub-linkages, some of which may be obsolete.
     * We know that pointSet is a WebMercatorGridPointSet, but if it's a new one we want to replicate its
     * linkages based on the base scenarioNetwork.gridPointSet's linkages. We don't know if it's new, so such
     * logic has to happen in the loop below over all streetModes, where we fetch and build the egress cost
     * tables. We already know for sure this is a scenarioNetwork.
     * So if we see a linkage for scenarioNetwork.gridPointSet, we need to add another linkage.
     * When this mapping exists:
     * (scenarioNetwork.gridPointSet, StreetLayer, StreetMode) -> linkageFor(scenarioNetwork.gridPointSet)
     * We need to generate this mapping:
     * (pointSet, StreetLayer, StreetMode) -> new LinkedPointSet(linkageFor(scenarioNetwork.gridPointSet), pointSet);
     * Note that: ((WebMercatorGridPointSet)pointSet).base == scenarioNetwork.gridPointSet
     * I'm afraid BaseLinkage means two different things here: we can subset bigger linkages that already
     * exist, or we can redo subsets of linkages of the same size when applying scenarios.
     * Yes: in one situation, the PointSet objects are identical when making the new linkage, but the
     * streetLayer differs. In the other situation, the PointSet objects are different but the other aspects
     * are the same. Again this is the difference between a PointSet and its linkage. We should call them
     * PointSetLinkages instead of LinkedPointSets because they do not subclass PointSet.
     * basePointSet vs. baseStreetLayer vs. baseLinkage.
     */
    private class LinkageCacheLoader extends CacheLoader<Key, LinkedPointSet> implements Serializable {
        @Override
        public LinkedPointSet load(Key key) {
            LOG.info("Building Linkage for {} because it was not found in cache.", key);

            // Case 1: This is a web mercator grid pointset which has a basePointSet (a supergrid), and we already
            // have a linkage for that basePointSet, for exactly the same streetLayer and mode. Just cut a smaller
            // linkage out of the bigger one. LinkageCache.this accesses the instance of the outer class.
            if (key.pointSet instanceof WebMercatorGridPointSet) {
                WebMercatorGridPointSet keyPointSet = (WebMercatorGridPointSet) key.pointSet;
                WebMercatorGridPointSet basePointSet = keyPointSet.basePointSet;
                if (basePointSet != null) {
                    LinkedPointSet basePointSetLinkage = LinkageCache.this.getLinkage(
                            basePointSet,
                            key.streetLayer,
                            key.streetMode
                    );
                    if (basePointSetLinkage != null && basePointSet.extents.zoom == keyPointSet.extents.zoom) {
                        LOG.info("Cutting linkage for {} out of existing linkage for {}.", keyPointSet, basePointSet);
                        return new LinkedPointSet(basePointSetLinkage, keyPointSet);
                    }
                }
            }

            // Case 2: We may already have a linkage for exactly the same PointSet, but for the base street layer
            // on which a scenario street layer was built.
            // If this StreetLayer is a part of a scenario and is therefore wrapping a base StreetLayer we need
            // to recursively fetch / create a linkage for that base StreetLayer so we don't duplicate work.
            LinkedPointSet baseLinkage = null;
            if (key.streetLayer.isScenarioCopy()) {
                LOG.info("Basing linkage for ({}, {}) on the linkage for ({}, {}).",
                    key.streetLayer,
                    key.streetMode,
                    key.streetLayer.baseStreetLayer,
                    key.streetMode
                );
                // LinkageCache.this accesses the instance of the outer class.
                baseLinkage = LinkageCache.this.getLinkage(
                    key.pointSet,
                    key.streetLayer.baseStreetLayer,
                    key.streetMode
                );
            }

            // Build a new linkage from this PointSet to the supplied StreetNetwork,
            // initialized with the existing linkage to the base StreetNetwork when relevant.
            return new LinkedPointSet(key.pointSet, key.streetLayer, key.streetMode, baseLinkage);
        }
    }

    /**
     * Build a linkage and store it, bypassing the PointSet's internal cache of linkages because we want this
     * particular linkage to be serialized with the network (the Guava cache does not serialize its contents) and
     * never evicted. The newly constructed linkage will also have an EgressCostTable built (since that's actually
     * the slowest part of linkage, and one we want to serialize for later reuse).
     */
    public void buildUnevictableLinkage (PointSet pointSet, StreetLayer streetLayer, StreetMode mode) {
        Key key = new Key(pointSet, streetLayer, mode);
        if (linkageMap.containsKey(key) || linkageCache.getIfPresent(key) != null) {
            throw new RuntimeException("Un-evictable linkage is being built more than once.");
        }
        LinkedPointSet newLinkage = new LinkedPointSet(pointSet, streetLayer, mode, null);
        // Pre-build the cost tables so it isn't done lazily later.
        newLinkage.getEgressCostTable();
        linkageMap.put(key, newLinkage);
    }

    public LinkageCache () {
        this.linkageCache = CacheBuilder.newBuilder()
                .maximumSize(LINKAGE_CACHE_SIZE)
                .removalListener(notification -> LOG.warn(
                        "LINKAGE CACHE EVICTION. key: {}, cause: {}",
                        notification.getKey(),
                        notification.getCause()))
                .build(new LinkageCacheLoader());
    }

    /**
     * Find or build a linkage associating each feature in this PointSet with a nearby edge in the StreetLayer.
     * This is a rather slow operation involving a lot of geometry calculations, so we cache the resulting
     * LinkedPointSets. This method returns a linkage from the cache if this operation has already been performed.
     */
    public LinkedPointSet getLinkage (PointSet pointSet, StreetLayer streetLayer, StreetMode streetMode) {
        try {
            Key key = new Key(pointSet, streetLayer, streetMode);
            LOG.info("Seeking linkage for {} in cache...", key);
            LinkedPointSet value = linkageMap.get(key);
            // Try the unevictable map before falling back on the evictable cache.
            if (value == null) {
                value = linkageCache.get(key);
            }
            return value;
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to link PointSet to StreetLayer for StreetMode.", e);
        }
    }

    /**
     * Combines the attributes that uniquely identify a linkage.
     */
    private static class Key implements Serializable {
        PointSet pointSet;
        StreetLayer streetLayer;
        StreetMode streetMode;

        public Key (PointSet pointSet, StreetLayer streetLayer, StreetMode streetMode) {
            this.pointSet = pointSet;
            this.streetLayer = streetLayer;
            this.streetMode = streetMode;
        }

        @Override
        public boolean equals (Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Key key = (Key) o;
            return Objects.equals(pointSet, key.pointSet)
                    && Objects.equals(streetLayer, key.streetLayer)
                    && streetMode == key.streetMode;
        }

        @Override
        public int hashCode () {
            return Objects.hash(pointSet, streetLayer, streetMode);
        }

        @Override
        public String toString () {
            return String.format("(%s, %s, %s)", pointSet, streetLayer, streetMode);
        }
    }

}
