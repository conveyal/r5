package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;

/**
 * Traversal permission labeler that restricts walking on most driving ways (useful for networks with complete
 * sidewalks). Also includes permissions for the United States (see USTraversalPermissionLabeler).
 */
public class NoMotorwayTraversalPermissionLabeler extends TraversalPermissionLabeler {
    static {
        addPermissions("pedestrian", "bicycle=yes");
        addPermissions("bridleway", "bicycle=yes;foot=yes"); //horse=yes but we don't support horse
        addPermissions("cycleway", "bicycle=yes;foot=yes");
        addPermissions("trunk|primary|secondary|tertiary|unclassified|residential|living_street|road|service|track",
                "access=yes");
    }

    @Override
    public RoadPermission getPermissions(Way way) {
        RoadPermission rp = super.getPermissions(way);
        if (way.hasTag("highway", "motorway")) {
            rp.forward.remove(EdgeStore.EdgeFlag.ALLOWS_CAR);
            rp.forward.remove(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR);
            rp.backward.remove(EdgeStore.EdgeFlag.ALLOWS_CAR);
            rp.backward.remove(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR);
        }
        return rp;
    }

}
