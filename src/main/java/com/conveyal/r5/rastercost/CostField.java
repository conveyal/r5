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
 * Subclasses or plugin functions will provide costs for hills, sun, noise, pollution etc.
 *
 * Interface-wise, a CostField is like a TraversalTimeCalculator with no turn costs, but it can transform an existing
 * cost. So TraversalTimeCalculator is like a special case of CostField that is always called first with a
 * parameter of zero, and has the added ability to calculate turn costs.
 *
 * TODO rename to TraversalTimeTransformer or TraversalCost
 */
public interface CostField {

    /**
     * TODO perhaps this and the base traversal time function should all return doubles, which are rounded only
     *   once after they have all been applied.
     */
    int transformTraversalTimeSeconds (EdgeStore.Edge currentEdge, int traversalTimeSeconds);

    /**
     * A unique name to identify this cost field for display on a map. It should be usable as a JSON key, so it should
     * not contain any spaces or non-alphanumeric characters.
     */
    String getDisplayKey ();

    /**
     * Returns a length-independent value associated with a particular edge for the purpose of display on a map.
     */
    double getDisplayValue (int edgeIndex);

    /** Interface for classes that create a CostField for a given StreetLayer, usually by overlaying a raster file. */
    interface Loader {
        void setNorthShiftMeters (double northShiftMeters);
        void setEastShiftMeters (double eastShiftMeters);
        void setInputScale (double inputScale);
        void setOutputScale (double outputScale);
        CostField load (StreetLayer streets);
    }

}
