package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;

/**
 * USTraversalPermissionLabeler, except biking is disallowed on ways with footway=sidewalk tag
 */
public class NoSidewalkCyclingTraversalPermissionLabeler extends USTraversalPermissionLabeler {
    @Override
    public RoadPermission getPermissions(Way way) {
        RoadPermission rp = super.getPermissions(way);
        if (way.hasTag("footway", "sidewalk")) {
            rp.forward.remove(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            rp.backward.remove(EdgeStore.EdgeFlag.ALLOWS_BIKE);
        }
        return rp;
    }

}
