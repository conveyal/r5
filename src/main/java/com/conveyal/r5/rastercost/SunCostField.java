package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.EdgeStore;
import com.esotericsoftware.minlog.Log;
import gnu.trove.list.TFloatList;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TFloatArrayList;
import gnu.trove.list.array.TIntArrayList;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.util.factory.Hints;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static com.conveyal.r5.analyst.Grid.LOG;

/**
 * Represents the additional cost of traversing edges that are not shaded from the sun.
 * Requires a detailed input raster of which...
 * On second thought, after looking at the tree canopy, it tends to consist of many disjoint trees. Maybe we want to
 * just sample at high resolution and store the "profile" in a bitset.
 * We could also just look back at the raster on every request to split the new edges. But then every worker needs
 * to download this huge raster. Not sure if that's bigger or smaller than the data stored on the edges.
 */
public class SunCostField implements CostField, Serializable {

    public static final BitSet ALL_TRUE = BitSet.valueOf(new byte[] { 1 });
    public static final BitSet ALL_FALSE = BitSet.valueOf(new byte[] { 0 });

    /** For each edge in the network, a BitSet containing the sun/shade profile sampled every N meters along the edge. */
    List<BitSet> sunOnEdge = new ArrayList<>();
    TFloatList sunProportions = new TFloatArrayList();

    /** Multiplicative factor applied to traversal time in the sun (as opposed to 1.0 for traversing in shade). */
    private final double sunPenalty;

    private final double sampleSpacingMeters;

    public SunCostField (double sunPenalty, double sampleSpacingMeters) {
        this.sunPenalty = sunPenalty;
        this.sampleSpacingMeters = sampleSpacingMeters;
    }

    @Override
    public int transformTraversalTimeSeconds (EdgeStore.Edge currentEdge, int traversalTimeSeconds) {
        float sunProportion = sunProportions.get(currentEdge.getEdgeIndex() / 2);
        double sunFactor = (1.0D - sunProportion) + (sunProportion * sunPenalty);
        // System.out.println("sun factor " + sunFactor);
        return (int) Math.round(sunFactor  * traversalTimeSeconds);
    }

    @Override
    public String getDisplayKey () {
        return "sun";
    }

    @Override
    public double getDisplayValue (int edgeIndex) {
        return sunProportions.get(edgeIndex / 2);
    }
}
