package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.IntHashGrid;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Point;
import org.mapdb.Fun.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;

/**
 * A PointSet represents a set of geographic points, which serve as destinations or "opportunities" in an
 * accessibility analysis.
 *
 * Although we currently only use one implementation of this abstract class, which represents an implicit set of
 * points on a regular grid in the web Mercator projection, we're keeping the abstraction so we can one day (re)-
 * implement freeform sets of points that are not on grids.
 */
public abstract class PointSet {

    private static final Logger LOG = LoggerFactory.getLogger(PointSet.class);

    /**
     * Maximum number of street network linkages to cache per PointSet. This is a crude way of limiting memory
     * consumption, and should eventually be replaced with a WeighingCache. Since every Scenario has its own StreetLayer
     * instance now, this means we can hold e.g. walk and bike linkages (with distance tables) for 3 scenarios at once.
     * Note that the baseline scenario also uses up slots in the cache, but is very quick to re-load because it's an
     * exact copy of the pre-built base linkage saved with the network.
     */
    public static int LINKAGE_CACHE_SIZE = 6;

    /**
     * When this PointSet is connected to the street network, the resulting data are cached in this Map to speed up
     * later reuse. Different linkages are produced for different street networks and for different on-street modes
     * of travel. At first we were careful to key this cache on the StreetNetwork itself (rather than the
     * TransportNetwork or Scenario) to ensure that linkages were re-used for multiple scenarios that have the same
     * street network. However, selectively re-linking to the street network is now usually fast, and StreetNetworks
     * must be copied for every scenario due to references to their containing TransportNetwork.
     * Note that this cache will be serialized with the PointSet, but serializing a Guava cache only serializes the
     * cache instance and its settings, not the contents of the cache. We consider this sane behavior.
     * TODO replace linkage cache with a manually managed, non-transient map outside the pointSets themselves.
     */
    protected transient LoadingCache<Tuple2<StreetLayer, StreetMode>, LinkedPointSet> linkageCache;

    /**
     * This Map augments the LoadingCache with linkages that should never be evicted.
     * The original base linkage for a network (a walk mode linkage for the entire region) should never be evicted.
     * There is a reference to it in the Network instance, so that linkage (and its distance tables) are always using
     * space in memory. So there is zero additional cost to keep them in cache forever.
     */
    protected Map<Tuple2<StreetLayer, StreetMode>, LinkedPointSet> linkageMap = new HashMap<>();

    /**
     * Build a linkage and store it, bypassing the PointSet's internal cache of linkages because we want this particular
     * linkage to be serialized with the network (the Guava cache does not serialize its contents) and never evicted.
     */
    public void buildUnevictableLinkage(StreetLayer streetLayer, StreetMode mode) {
        Tuple2<StreetLayer, StreetMode> key = new Tuple2<>(streetLayer, mode);
        if (linkageMap.containsKey(key) || linkageCache.getIfPresent(key) != null) {
            LOG.error("Un-evictable linkage is being built more than once.");
        }
        LinkedPointSet newLinkage = new LinkedPointSet(this, streetLayer, mode, null);
        linkageMap.put(key, newLinkage);
    }

    /**
     * The logic for lazy-loading linkages into the cache.
     */
    private class LinkageCacheLoader extends CacheLoader<Tuple2<StreetLayer, StreetMode>, LinkedPointSet> implements Serializable {
        @Override
        public LinkedPointSet load(Tuple2<StreetLayer, StreetMode> key) {
            LOG.info("Linkage for ({}, {}) was not found in cache, building it now.", key.a, key.b);
            // If this StreetLayer is a part of a scenario and is therefore wrapping a base StreetLayer we need
            // to recursively fetch / create a linkage for that base StreetLayer so we don't duplicate work.
            // PointSet.this accesses the instance of the outer class.
            LinkedPointSet baseLinkage = null;
            if (key.a.isScenarioCopy()) {
                baseLinkage = PointSet.this.getLinkage(key.a.baseStreetLayer, key.b);
            }
            // Build a new linkage from this PointSet to the supplied StreetNetwork,
            // initialized with the existing linkage to the base StreetNetwork when relevant.
            return new LinkedPointSet(PointSet.this, key.a, key.b, baseLinkage);
        }
    }

    /**
     * Makes it fast to get a set of all points within a given rectangle.
     * This is useful when finding distances from transit stops to points.
     * FIXME we don't need a spatial index to do this on a gridded pointset. Make an abstract method and implement on subclasses.
     * The spatial index is a hashgrid anyway though, not an STRtree, so it's more compact.
     * FIXME this is apparently ONLY used for selecting points for which to rebuild distance tables.
     * Can we just iterate and filter, and eliminate the index?
     */
    public transient IntHashGrid spatialIndex;

    /**
     * Constructor for a PointSet that initializes its cache of linkages upon deserialization.
     */
    public PointSet() {
        this.linkageCache = CacheBuilder.newBuilder().maximumSize(LINKAGE_CACHE_SIZE)
                .removalListener(notification -> LOG.warn("Linkage cache evicted {}, cause: {}",
                        notification.getKey(), notification.getCause()))
                .build(new LinkageCacheLoader());
    }

    /**
     * Find or build a linkage associating each feature in this PointSet with a nearby edge in the StreetLayer.
     * This is a rather slow operation involving a lot of geometry calculations, so we cache the resulting
     * LinkedPointSets. This method returns a linkage from the cache if this operation has already been performed.
     */
    public LinkedPointSet getLinkage (StreetLayer streetLayer, StreetMode streetMode) {
        try {
            Tuple2<StreetLayer, StreetMode> key = new Tuple2<>(streetLayer, streetMode);
            LOG.info("Seeking linkage for ({}, {}) in cache...", streetLayer, streetMode);
            LinkedPointSet value = linkageMap.get(key);
            if (value == null) {
                value = linkageCache.get(new Tuple2<>(streetLayer, streetMode));
            }
            if (value != null && value.pointSet != this) {
                throw new AssertionError("A PointSet should only hold linkages for itself, not for other PointSets.");
            }
            return value;
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to link PointSet to StreetLayer.", e);
        }
    }

    public abstract double getLat(int i);

    public abstract double getLon(int i);

    public abstract int featureCount();

    /**
     * Returns a new coordinate object for the feature at the given index in this set, or its centroid,
     * in FIXED POINT DEGREES.
     */
    public Coordinate getCoordinateFixed(int index) {
        return new Coordinate(floatingDegreesToFixed(getLon(index)), floatingDegreesToFixed(getLat(index)));
    }

    /**
     * Returns a new coordinate object for the feature at the given index in this set, or its centroid,
     * in FIXED POINT DEGREES.
     */
    public Point getJTSPointFixed(int index) {
        return GeometryUtils.geometryFactory.createPoint(getCoordinateFixed(index));
    }

    /**
     * If the spatial index of points in the pointset has not yet been made, create one.
     */
    public void createSpatialIndexAsNeeded() {
        if (spatialIndex != null) return;
        spatialIndex = new IntHashGrid();
        for (int p = 0; p < this.featureCount(); p++) {
            Envelope pointEnvelope = new Envelope(getCoordinateFixed(p));
            spatialIndex.insert(pointEnvelope, p);
        }
    }

}
