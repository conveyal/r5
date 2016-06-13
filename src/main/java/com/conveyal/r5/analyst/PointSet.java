package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Point;
import org.mapdb.Fun;
import org.mapdb.Fun.Tuple2;
import sun.awt.image.ImageWatched;

import java.util.concurrent.ExecutionException;

public abstract class PointSet {

    /** Maximum number of street network linkages to cache per PointSet. Affects memory consumption. */
    public static int LINKAGE_CACHE_SIZE = 5;

    public abstract int featureCount();

    public abstract Coordinate getCoordinate(int index);

    /**
     * When this PointSet is connected to the street network, the resulting data are cached in this Map to speed up
     * later reuse. Different linkages are produced for different street networks and for different on-street modes
     * of travel. We don't want to key this cache on the TransportNetwork or Scenario, only semantically on the
     * StreetNetwork. This ensures linkages are re-used for multiple scenarios that have different transit
     * characteristics but the same street network.
     * TODO change equals and hashcode of StreetLayer to use its ID, which will accelerate some of these operations by using semantic equality.
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


}
