package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;

/**
 * Assign traversal permissions to edges based on their tags in OpenStreetMap.
 * see https://wiki.openstreetmap.org/wiki/Computing_access_restrictions#Algorithm
 * and also prior work by Marko Burjek:
 * https://github.com/buma/OpenTripPlanner-Maribor/blob/8eafa3ad9f1426877c6da3d730eaea46c6de35cf/src/main/java/org/opentripplanner/streets/permissions/AccessRestrictionsAlgorithm.java
 *
 * This class is abstract. You must make a country-specific subclass containing defaults for a particular country,
 * see https://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Access-Restrictions
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
