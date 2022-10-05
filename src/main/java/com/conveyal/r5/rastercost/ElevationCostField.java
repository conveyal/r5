package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.Edge;
import com.conveyal.r5.streets.EdgeStore;
import gnu.trove.list.TFloatList;
import gnu.trove.list.TShortList;
import gnu.trove.list.array.TFloatArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.List;

/**
 * This represents the changes in elevation along every edge in the network, as well as the derived additional costs
 * for traversing those elevation changes.
 *
 * Inspecting the resulting edge elevation profiles reveals that a large number of them are the same elevation over the
 * entire length of the edge, or very smooth linear changes. These can be simplified and compressed in several ways:
 * 1. Where both end vertices and every sample point are at the same elevation, the sample points can simply be removed.
 * 2. Where the sample points fall roughly on a straight line between the two end vertices the sample points are not
 * needed. Item 1 is a special case of item 2.
 * 3. ...
 * 4. If after all these simplifications less than half of the edges have an explicit elevation profile, we may want to
 * store them in a map instead of an array for better cache efficiency.
 *
 * The underlying per-edge storage mechanism is not shared with the tree shade costs because they have different
 * characteristics. The tree shade is boolean and can be stored as only the distances along the edge where we enter or
 * exit shade.
 *
 * However, storing alternating (distance, elevation change) in a packed array of shorts or bytes would be much easier
 * to deal with when splitting edges.
 *
 * In this case tree shade might be able to share the storage model. The value change elements would all be +/- 1 which
 * would waste some space, but the code reuse might be worth it. And of course this would also allow greyscale shade
 * instead of binary shade inputs.
 */
public class ElevationCostField implements CostField, Serializable {

    public static final double ELEVATION_SAMPLE_SPACING_METERS = 10;
    private static final Logger LOG = LoggerFactory.getLogger(ElevationCostField.class);

    /**
     * This is actually independent of inputScale. It's an attempt to keep the storage of elevation data down to a
     * reasonable size since we are keeping a profile for every edge just to facilitate splitting them. Note that
     * vertexElevationsDecimeters is a TShortList and elevationProfilesDecimeters contains short arrays, so all our
     * elevation measurements take half as much space as 32 bit ints or a quarter as much as double precision floats.
     * 2^15/10 meters is enough to store all elevations outside very high mountain passes. This may be considered
     * premature or misguided optimization, its effectiveness should be evaluated.
     */
    public static final double DECIMETERS_PER_METER = 10;

    private final ElevationCostCalculator costCalculator;

    private final double outputScale;

    public ElevationCostField (ElevationCostCalculator costCalculator, double outputScale) {
        this.costCalculator = costCalculator;
        this.outputScale = outputScale;
    }

    @Override
    public int additionalTraversalTimeSeconds (Edge currentEdge, int traversalTimeSeconds) {
        return (int) Math.round(traversalTimeSeconds * edgeFactors.get(currentEdge.getEdgeIndex()));
    }

    @Override
    public String getDisplayKey () {
        return "elevation";
    }

    @Override
    public double getDisplayValue (int edgeIndex) {
        return edgeFactors.get(edgeIndex);
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
    public List<short[]> elevationProfilesDecimeters;

    /**
     * For each edge in the network (not edge pair), a traversal time multiplier.
     * This multiplier is not symmetric with respect to travel direction,
     * so unlike the elevation it's derived from it must be stored for each edge not edge pair.
     * The edge factors we store will yield the _difference_ in perceived travel time (cost) to be chained additively
     * Thus 0 means no adjustment to the base traversal time, and 1 means to double the base traversal time.
     */
    public TFloatList edgeFactors;

    /**
     * Call a function for every segment in this edge's elevation profile.
     * The order of the profile segments will always be that of the forward edge in the edge pair, but the sign of
     * the elevation change will be inverted for a backward edge. This is not appropriate for all imaginable consumers
     * but it works fine for a memoryless difficulty scaling function like the Tobler hiking function.
     */
    public void forEachElevationSegment (Edge edge, ElevationSegmentConsumer consumer) {
        consumer.reset(); // Zero out any state from previous usage of the instance.
        double remainingMeters = edge.getLengthM();
        // Do not go through getFromVertex method because we do not currently truly reverse edges.
        // This is only to keep the iteration logic below simple until we actually need reverse iteration.
        int pairIndex = edge.getEdgeIndex() / 2;
        boolean invert = edge.isBackward();
        int startVertex = edge.getEdgeStore().fromVertices.get(pairIndex);
        int endVertex = edge.getEdgeStore().toVertices.get(pairIndex);
        int i = 0;
        double prevElevationMeters = getVertexElevationMeters(startVertex);
        for (short elevationDecimeters : elevationProfilesDecimeters.get(pairIndex)) {
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
     * Both values are in meters. The reset() function allows consumer instances to be reused, which could lead to
     * thread safety issues. Considering how efficient Java garbage collection has become, we may want to change this
     * and require one instance per calculation with no reset().
     */
    public interface ElevationSegmentConsumer {
        void reset ();
        void consumeElevationSegment (int index, double xMeters, double yMeters);
    }

    /**
     * An interface for converting edge elevations into scaling factors representing exertion relative to movement
     * on flat ground. This is just an ElevationSegmentConsumer that has an additional method for retrieving the
     * weighted result for all segments in an edge.
     */
    public interface ElevationCostCalculator extends ElevationCostField.ElevationSegmentConsumer {
        /** Return a nonzero positive multiplier for edge traversal time normalized to 1 for flat ground. */
        double weightedElevationFactor ();
    }

    /**
     * Computing and averaging edge speed factors is extremely fast, on the order of 2 million edges per second.
     * It might be nice to bundle this into elevation sampling, but that's performed as a stream operation
     * and there's no straightforward way to return both the profile and speed factor together.
     * FIXME the ElevationCostCalculators are not threadsafe. They are curently used single-threaded in scenario or
     *       network builds. But if used in parallel requests at runtime, they'll need to be created on each operation.
     */
    public synchronized void computeWeightedAverages (EdgeStore edgeStore) {
        edgeFactors = new TFloatArrayList(edgeStore.nEdges());
        Edge edge = new Edge(edgeStore);
        for (int e = 0; e < edgeStore.nEdges(); ++e) {
            edge.seek(e);
            edgeFactors.add((float) weightedAverageForEdge(edge));
        }
    }

    /**
     * Adjust the factors returned by the elevation cost calculators, which should be 1 on flat ground,
     * to account for the user-specified outputScale and the fact that we want to return the _difference_ in cost
     * to be included in a series of additive terms.
     */
    private double weightedAverageForEdge (Edge edge) {

        forEachElevationSegment(edge, costCalculator);
        return (costCalculator.weightedElevationFactor() - 1) * outputScale;
    }

}
