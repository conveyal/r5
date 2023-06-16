package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.EdgeStore;
import gnu.trove.list.TFloatList;
import gnu.trove.list.array.TFloatArrayList;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Represents the additional cost of traversing edges that are not shaded from the sun.
 * This needs to support splitting the edges and getting accurate profiles for the split pieces.
 * In the data set this was designed for, the tree canopy tends to consist of many disjoint trees.
 * So rather than storing the points at which the street switches from sun to shade, we just sample at high resolution
 * and store the "profile" of shade along the street in a bitset.
 * We could in principle just look back at the original raster on every request to split the new edges.
 * But then every worker needs to download this huge raster. We may want to investigate whether this is bigger or
 * smaller than the bitest data stored on all the edges.
 */
public class SunCostField implements CostField, Serializable {

    public static final BitSet ALL_TRUE = BitSet.valueOf(new byte[] { 1 });
    public static final BitSet ALL_FALSE = BitSet.valueOf(new byte[] { 0 });

    /** For each edge in the network, a BitSet containing the sun/shade profile sampled every N meters along the edge. */
    List<BitSet> sunOnEdge = new ArrayList<>();

    /**
     * Number in the range 0...1 representing the proportion of each edge pair that is in the shade.
     * Floating point numbers are densest in this range, so 32 bits are more than sufficent.
     * These could probably be made 2-4x smaller by storing them in shorts or bytes; we should measure this.
     */
    TFloatList sunProportions = new TFloatArrayList();

    /**
     * Multiplicative factor applied to traversal time to determine extra cost of traversing in sun instead of shade.
     * 0.5 will make walking in the sun feel 1.5x worse than walking in the shade. Negative numbers with absolute
     * value less than 1 also work: -0.5 will penalize shade such that walking in the sun is twice as good as walking
     * in the shade.
     */
    private final double sunPenalty;

    private final double sampleSpacingMeters;

    public SunCostField (double sunPenalty, double sampleSpacingMeters) {
        this.sunPenalty = sunPenalty;
        this.sampleSpacingMeters = sampleSpacingMeters;
    }

    /**
     * TODO sunPenalty could be pre-multiplied into sunProportion, making this method unnecessary.
     * @return a multiplicative factor for a particular edge to yield the extra cost due to sun.
     */
    private double getSunFactor (int edgeIndex) {
        float sunProportion = sunProportions.get(edgeIndex / 2);
        double sunFactor = sunProportion * sunPenalty;
        return sunFactor;
    }

    @Override
    public int additionalTraversalTimeSeconds (EdgeStore.Edge currentEdge, int traversalTimeSeconds) {
        double sunFactor = getSunFactor(currentEdge.getEdgeIndex());
        return (int) Math.round(sunFactor * traversalTimeSeconds);
    }

    @Override
    public String getDisplayKey () {
        return "sun";
    }

    @Override
    public double getDisplayValue (int edgeIndex) {
        return getSunFactor(edgeIndex);
    }

}
