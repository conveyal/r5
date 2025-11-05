package com.conveyal.r5.rastercost;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.TraversalTimeCalculator;
import com.conveyal.r5.streets.VertexStore;
import gnu.trove.list.TFloatList;
import gnu.trove.list.TShortList;

import java.io.File;
import java.util.List;

/**
 * This models a field of traversal costs, in the sense of a cost that varies over geographic space having different
 * effects on different edges. Subclasses serve as plugins that provide costs for hills, sun, noise, pollution etc.
 *
 * CostFields could in principle apply costs to any mode of travel, and might yield different costs for different modes,
 * but currently they are not mode-aware. No existing implementations apply to cars, so for now CostFields are simply
 * ignored for car routing. Ideally CostFields would know which modes they applied to, and the list of CostFields would
 * be filtered before the street search happens. The logic currently in the StreetRouter constructor could be factored
 * into a mode-aware factory method that would yield the correct TraversalTimeCalculator. StreetRouter is explicitly
 * single-use with a single StreetMode. That mode could be specified up front and the TraversalTimeCalculator 
 * chosen accordingly (avoiding the MultistageTraversalTimeCalculator construct entirely when no CostFields apply).
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
    int additionalTraversalTimeSeconds (EdgeStore.Edge currentEdge, int baseTraversalTimeSeconds);

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
