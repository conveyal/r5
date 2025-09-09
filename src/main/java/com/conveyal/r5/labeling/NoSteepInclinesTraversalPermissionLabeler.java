package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;

/**
 * USTraversalPermissionLabeler, except walking is disallowed on steep ways, where steep is defined as having an
 * "incline" tag (e.g., from OSW rather than typical OSM source) with a corresponding absolute value in excess of 0.05
 */
public class NoSteepInclinesTraversalPermissionLabeler extends USTraversalPermissionLabeler {
    @Override
    public RoadPermission getPermissions(Way way) {
        RoadPermission rp = super.getPermissions(way);
        if (way.hasTag("incline") && Math.abs(Double.parseDouble(way.getTag("incline"))) > 0.05) {
            rp.forward.remove(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            rp.backward.remove(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        }
        return rp;
    }

}
