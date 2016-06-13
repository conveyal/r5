package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.vividsolutions.jts.geom.Coordinate;
import org.mapdb.Fun.Tuple2;

import java.util.concurrent.ExecutionException;

public abstract class PointSet {

    /** Maximum number of street network linkages to cache per PointSet. Affects memory consumption. */
    public static int LINKAGE_CACHE_SIZE = 5;

    /**
     * When this PointSet is connected to the street network, the resulting data are cached in this Map to speed up
     * later reuse. Different linkages are produced for different street networks and for different on-street modes
     * of travel. We don't want to key this cache on the TransportNetwork or Scenario, only on the StreetNetwork.
     * This ensures linkages are re-used for multiple scenarios that have different transit characteristics but the
     * same street network.
     * R5 is now smart enough to only clone the StreetLayer when it's really changed by a scenario, so we can key on
     * the StreetLayer's identity rather than semantically.
     */
    protected LoadingCache<Tuple2<StreetLayer, StreetMode>, LinkedPointSet> linkageCache = CacheBuilder.newBuilder()
            .maximumSize(LINKAGE_CACHE_SIZE)
            // Unfortunately Java can't seemt to infer the types for a lambda function here.
            .build(new CacheLoader<Tuple2<StreetLayer, StreetMode>, LinkedPointSet>() {
                @Override public LinkedPointSet load(Tuple2<StreetLayer, StreetMode> key) throws Exception {
                    // PointSet.this accesses outer class
                    return new LinkedPointSet(PointSet.this, key.a, key.b);
                }
            });

    /**
     * Associate each feature in this PointSet with a nearby street edge in the StreetLayer of the supplied
     * TransportNetwork. This is a rather slow operation involving a lot of geometry calculations, so we cache these
     * LinkedPointSets. This method returns one from the cache if this operation has already been performed.
     */
    public LinkedPointSet link (StreetLayer streetLayer, StreetMode streetMode) {
        try {
            return linkageCache.get(new Tuple2<>(streetLayer, streetMode));
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to link PointSet to StreetLayer.", e);
        }
    }

    public abstract double getLat(int i);

    public abstract double getLon(int i);

    public abstract int featureCount();

    /** Returns a new coordinate object for the feature at the given index in this set, or its centroid. */
    public Coordinate getCoordinate(int index) {
        return new Coordinate(getLon(index), getLat(index));
    }

}
