package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;

/**
 * USTraversalPermissionLabeler, except walking is disallowed on ways that allow cars, ways with inclines
 * steeper than a specified limit, and ways with steps.
 */
public class StepFreeTraversalPermissionLabeler extends USTraversalPermissionLabeler {

    // Passed to StreetLayer#isImpassable to block traversal at certain nodes
    public boolean requireStepFree = true;
    Double maxIncline;

    public StepFreeTraversalPermissionLabeler (Double maxIncline) {
        if (maxIncline != null) this.maxIncline = maxIncline;
    }
    @Override
    public RoadPermission getPermissions(Way way) {
        RoadPermission rp = super.getPermissions(way);

        // Disallow walking on ways that allow cars
        if (rp.forward.contains(EdgeStore.EdgeFlag.ALLOWS_CAR) ||
                rp.forward.contains(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR) ||
                rp.backward.contains(EdgeStore.EdgeFlag.ALLOWS_CAR) ||
                rp.backward.contains(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR)
        ) {
            rp.disallowPedestrians();
        }

        // Disallow walking on ways with slopes steeper than specified maxIncline
        else if  (maxIncline != null) {
            if (way.hasTag("incline") && Math.abs(Double.parseDouble(way.getTag("incline"))) > maxIncline) {
                rp.disallowPedestrians();
            }
        }

        // Disallow walking on ways that have steps
        if (way.hasTag("highway", "steps")) {
            rp.disallowPedestrians();
        }
        return rp;
    }
}
