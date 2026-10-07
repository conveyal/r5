package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.streets.EdgeStore;

/**
 * USTraversalPermissionLabeler, except walking is disallowed on ways that allow cars and ways with inclines
 * steeper than a specified limit.
 */
public class NoSteepInclinesTraversalPermissionLabeler extends SidewalkTraversalPermissionLabeler {

    Double maxIncline;
    public NoSteepInclinesTraversalPermissionLabeler (TransportNetworkConfig config) {
        super(config);
        if (config != null) {
            if (config.maxIncline != null) this.maxIncline = config.maxIncline;
        }
    }
    @Override
    public RoadPermission getPermissions(Way way) {
        // Base class (SidewalkTraversalPermissionLabeler) disallows walking on ways that allow cars
        RoadPermission rp = super.getPermissions(way);

        // Disallow walking on ways with slopes steeper than specified maxIncline
        if  (maxIncline != null) {
            if (way.hasTag("incline") && Math.abs(Double.parseDouble(way.getTag("incline"))) > maxIncline) {
                rp.disallowPedestrians();
            }
        }

        return rp;
    }

}
