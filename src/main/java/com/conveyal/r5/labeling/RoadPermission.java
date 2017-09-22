package com.conveyal.r5.labeling;

import com.conveyal.r5.streets.EdgeStore;

import java.util.EnumSet;

/**
 * Class which has both forward and backward flag permissions
 *
 * It is used in return from {@link TraversalPermissionLabeler}
 * Created by mabu on 30.11.2015.
 */
public class RoadPermission {
    public final EnumSet<EdgeStore.EdgeFlag> forward;
    public final EnumSet<EdgeStore.EdgeFlag> backward;

    public RoadPermission(EnumSet<EdgeStore.EdgeFlag> forward,
        EnumSet<EdgeStore.EdgeFlag> backward) {
        this.forward = forward;
        this.backward = backward;

    }
}
