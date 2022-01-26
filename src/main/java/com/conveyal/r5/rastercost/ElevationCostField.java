package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.EdgeStore;
import gnu.trove.list.TFloatList;
import gnu.trove.list.TShortList;
import gnu.trove.list.array.TFloatArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.conveyal.r5.rastercost.ElevationLoader.ELEVATION_SAMPLE_SPACING_METERS;
import static com.conveyal.r5.rastercost.ToblerCalculator.DECIMETERS_PER_METER;

public class ElevationCostField implements CostField {

    private static final Logger LOG = LoggerFactory.getLogger(ElevationCostField.class);

    @Override
    public int transformTraversalTimeSeconds (EdgeStore.Edge currentEdge, int traversalTimeSeconds) {
        return (int)Math.round(((double)traversalTimeSeconds) / factorForEdge(currentEdge));
    }

    /**
     * The elevation of each vertex in the network in decimeters.
     * This could be relative to the geoid or the spheroid depending on your data source.
     * It doesn't matter as long as only relative heights are used.
     */
    public TShortList vertexElevationsDecimeters;

    /** Perform the conversion from decimeters to meters. */
    private double getVertexElevationMeters (int vidx) {
        return vertexElevationsDecimeters.get(vidx) / DECIMETERS_PER_METER;
    };

    /**
     * Vertical elevation values for points along edges. One entry for each edge PAIR. These do not include the
     * elevation of the start and end vertices, only evenly-spaced points along the interior of the edge geometry.
     * Edges that are short enough will not contain any interior points, and will have an empty array (not null).
     * Units are decimeters, which fit in a 16-bit signed integer for all but the highest mountain roads in the world.
     */
    public List<short[]> elevationProfiles;

    /**
     * For each edge in the network (not edge pair), an a multiplier. This multiplier is not symmetric with respect to
     * travel direction, so unlike the elevation it's derived from it must be stored for each edge not edge pair.
     */
    public TFloatList toblerAverages;

    public double factorForEdge (EdgeStore.Edge edge) {
        return toblerAverages.get(edge.getEdgeIndex());
    }

    /**
     * Call a function for every segment in this edge's elevation profile.
     * The order of the profile segments will always be that of the forward edge in the edge pair, but the sign of
     * the elevation change will be inverted for a backward edge. This is not appropriate for all imaginable consumers
     * but it works fine for a memoryless difficulty scaling function like the Tobler hiking function.
     */
    public void forEachElevationSegment (EdgeStore.Edge edge, ElevationSegmentConsumer consumer) {
        double remainingMeters = edge.getLengthM();
        // Do not go through getFromVertex method because we do not currently truly reverse edges.
        // This is only to keep the iteration logic below simple until we actually need reverse iteration.
        int pairIndex = edge.getEdgeIndex() / 2;
        boolean invert = edge.isBackward();
        int startVertex = edge.getEdgeStore().fromVertices.get(pairIndex);
        int endVertex = edge.getEdgeStore().toVertices.get(pairIndex);
        int i = 0;
        double prevElevationMeters = getVertexElevationMeters(startVertex);
        for (short elevationDecimeters : elevationProfiles.get(pairIndex)) {
            final double elevationMeters = elevationDecimeters / DECIMETERS_PER_METER;
            final double elevationChangeMeters = delta(prevElevationMeters, elevationMeters, invert);
            consumer.consumeElevationSegment(i, ELEVATION_SAMPLE_SPACING_METERS, elevationChangeMeters);
            remainingMeters -= ELEVATION_SAMPLE_SPACING_METERS;
            i += 1;
            prevElevationMeters = elevationMeters;
        }
        // The remainder may be slightly larger than the normal sample spacing due to differing distance approximations.
        if (remainingMeters >= 0 && remainingMeters <= ELEVATION_SAMPLE_SPACING_METERS + 0.5) {
            final double elevationChangeMeters = delta(prevElevationMeters, getVertexElevationMeters(endVertex), invert);
            consumer.consumeElevationSegment(i, remainingMeters, elevationChangeMeters);
        } else {
            LOG.warn("Unexpected remainder {} m on edge of length {} m", remainingMeters, edge.getLengthM());
        }
    }

    private static double delta (double prev, double curr, boolean invert) {
        if (invert) {
            return prev - curr;
        } else {
            return curr - prev;
        }
    }

    /**
     * A functional interface that consumes segments in a street edge's elevation profile one by one.
     * Each segment is represented as a distance across the ground (always positive) and a signed vertical change.
     */
    @FunctionalInterface
    public static interface ElevationSegmentConsumer {
        public void consumeElevationSegment (int index, double xMeters, double yMeters);
    }

    public void computeToblerAverages (EdgeStore edgeStore) {
        // Computing and averaging Tobler factors is extremely fast, on the order of 2 million edges per second.
        // It might be nice to bundle this into elevation sampling, but that's performed as a stream operation
        // and there's no straightforward way to return both the profile and Tobler average.
        toblerAverages = new TFloatArrayList(edgeStore.nEdges());
        EdgeStore.Edge edge = edgeStore.getCursor();
        for (int e = 0; e < edgeStore.nEdges(); ++e) {
            edge.seek(e);
            toblerAverages.add((float) weightedAverageForEdge(edge));
        }
    }

    private double weightedAverageForEdge (EdgeStore.Edge edge) {
        ToblerCalculator calculator = new ToblerCalculator();
        this.forEachElevationSegment(edge, calculator);
        return calculator.weightedToblerAverage();
    }

}
