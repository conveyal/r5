package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.streets.EdgeStore;

/**
 * Traversal permission labeler that restricts walking on most driving ways (useful for networks with complete
 * sidewalks). Also includes permissions for the United States (see USTraversalPermissionLabeler).
 */
public class SidewalkTraversalPermissionLabeler extends TraversalPermissionLabeler {
    static {
        addPermissions("pedestrian", "bicycle=yes");
        addPermissions("bridleway", "bicycle=yes;foot=yes"); //horse=yes but we don't support horse
        addPermissions("cycleway", "bicycle=yes;foot=yes");
        addPermissions("trunk|primary|secondary|tertiary|unclassified|residential|living_street|road|service|track",
                "access=yes");
    }

    public SidewalkTraversalPermissionLabeler (TransportNetworkConfig config) {
        super(config);
    }

    @Override
    public RoadPermission getPermissions(Way way) {
        RoadPermission rp = super.getPermissions(way);
        if (rp.forward.contains(EdgeStore.EdgeFlag.ALLOWS_CAR) ||
            rp.forward.contains(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR) ||
            rp.backward.contains(EdgeStore.EdgeFlag.ALLOWS_CAR) ||
            rp.backward.contains(EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR)
        ) {
            rp.disallowPedestrians();
        }
        return rp;
    }

}
