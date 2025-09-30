package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;

/**
 * USTraversalPermissionLabeler, except walking is disallowed on steps and most driving ways
 */
public class StepFreeTraversalPermissionLabeler extends USTraversalPermissionLabeler {

    public boolean requireStepFree = true;
    @Override
    public RoadPermission getPermissions(Way way) {
        RoadPermission rp = super.getPermissions(way);
        if (rp.forward.contains(EdgeStore.EdgeFlag.ALLOWS_CAR) ||
                rp.forward.contains(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR) ||
                rp.backward.contains(EdgeStore.EdgeFlag.ALLOWS_CAR) ||
                rp.backward.contains(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR)
        ) {
            rp.forward.remove(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            rp.forward.remove(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_PEDESTRIAN);
            rp.backward.remove(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            rp.backward.remove(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_PEDESTRIAN);
        }
        if (way.hasTag("highway", "steps")) {
            rp.forward.remove(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            rp.backward.remove(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        }
        return rp;
    }

}
