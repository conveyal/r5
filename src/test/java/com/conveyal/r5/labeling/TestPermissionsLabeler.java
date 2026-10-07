package com.conveyal.r5.labeling;

import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;

/**
 * This is used for tests. It adds new highway tag highway=nobikenoped which forbids cycling and walking. But allows other things.
 * Created by mabu on 27.11.2015.
 */
public class TestPermissionsLabeler extends USTraversalPermissionLabeler {
    static {
        addPermissions("highway=nobikenoped", "access=yes;bicycle=no;foot=no");
    }

    public TestPermissionsLabeler () {
        super(null);
    }
}
