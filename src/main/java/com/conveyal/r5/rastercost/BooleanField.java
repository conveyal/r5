package com.conveyal.r5.rastercost;

import java.util.BitSet;

/**
 * Represents a field where every point has a true or false value.
 * The field has been interpolated and sampled along street edges, the original field source (raster) is not retained.
 */
// TreeShadeScalarField
public class BooleanField {

    public BitSet vertexValues = new BitSet(); // Initialize with size. Can grow.

    /**
     * One entry per edge pair. For edges whose values change from one end to the other, a list of distances at which
     * the value transitions from true to false or vice versa. For edges with no change, null.
     * Could be optimized to short values holding decimeters.
     */
    public int[][] breakpoints;

    public int lengthWherePositive (int edgePairIndex) {
        return 0;
    }

}
