package com.conveyal.r5.analyst;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Point;
import org.mapdb.Fun.Tuple2;

import java.util.concurrent.ExecutionException;

import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;

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
            .build(new CacheLoader<Tuple2<StreetLayer, StreetMode>, LinkedPointSet>() {
                @Override public LinkedPointSet load(Tuple2<StreetLayer, StreetMode> key) throws Exception {
                    // If this StreetLayer is a part of a scenario and is therefore wrapping a base StreetLayer we need
                    // to recursively fetch / create a linkage for that base StreetLayer so we don't duplicate work.
                    // PointSet.this accesses the instance of the outer class.
                    LinkedPointSet baseLinkage = null;
                    if (key.a.isScenarioCopy()) {
                        baseLinkage = PointSet.this.linkageCache.get(new Tuple2<>(key.a.baseStreetLayer, key.b));
                    }
                    // Build a new linkage from this PointSet to the supplied StreetNetwork,
                    // initialized with the existing linkage to the base StreetNetwork when relevant.
                    return new LinkedPointSet(PointSet.this, key.a, key.b, baseLinkage);
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

    /** Returns a new coordinate object for the feature at the given index in this set, or its centroid, in FIXED POINT DEGREES. */
    public Coordinate getCoordinateFixed(int index) {
        return new Coordinate(floatingDegreesToFixed(getLon(index)), floatingDegreesToFixed(getLat(index)));
    }

    /** Returns a new coordinate object for the feature at the given index in this set, or its centroid, in FIXED POINT DEGREES. */
    public Point getJTSPointFixed(int index) {
        return GeometryUtils.geometryFactory.createPoint(getCoordinateFixed(index));
    }

}
