package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.Edge;
import com.conveyal.r5.streets.StreetLayer;

/**
 * Subclasses or plugin functions will provide costs for hills, sun, noise, pollution etc.
 *
 * Interface-wise, a CostField is like a TraversalTimeCalculator with no turn costs, which transforms an existing
 * traversal time rather than starting from scratch. So TraversalTimeCalculator is like a special case of CostField
 * that is always called first with an input traversalTimeSeconds of zero, and can also calculate turn costs.
 */
public interface CostField {

    /**
     * Return a number of (perceived) seconds to add or subtract from the baseTraversalTimeSeconds due to an additional
     * consideration such as elevation change or sun or noise exposure. This is typically computed by multiplying the
     * base traversal time by a per-edge factor, but other approaches may be used. Negative values may be returned,
     * but if the sum of additionalTraversalTimeSeconds for all different CostFields yields a negative overall
     * traversal time for an edge during routing, this time will be clamped to the smallest allowed value (1 second).
     * TODO This and the base traversal time function could all return doubles, which are rounded only once after they
     *      have all been summed. This should reduce roundoff error.
     */
    int additionalTraversalTimeSeconds (Edge currentEdge, int baseTraversalTimeSeconds);

    /**
     * A unique name to identify this cost field for display on a map. It should be usable as a JSON key, so it should
     * not contain any spaces or non-alphanumeric characters.
     */
    String getDisplayKey ();

    /**
     * Returns a length-independent value associated with a particular edge for the purpose of display on a map.
     * Typically a multiplier that this class applies to the base traversal cost to find additional traversal cost.
     */
    double getDisplayValue (int edgeIndex);

    /** Interface for classes that create a CostField for a given StreetLayer, usually by overlaying a raster file. */
    interface Loader<T extends CostField> {
        void setNorthShiftMeters (double northShiftMeters);
        void setEastShiftMeters (double eastShiftMeters);
        void setInputScale (double inputScale);
        void setOutputScale (double outputScale);
        T load (StreetLayer streets);
    }

}
