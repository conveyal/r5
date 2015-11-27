package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.*;

/**
 * Created by mabu on 27.11.2015.
 */
public class TypeOfEdgeLabelerTest {

    static TypeOfEdgeLabeler typeOfEdgeLabeler;
    static TraversalPermissionLabeler traversalPermissionLabeler;

    @BeforeClass
    public static void setUpClass() {
        typeOfEdgeLabeler = new TypeOfEdgeLabeler();
        traversalPermissionLabeler = new USTraversalPermissionLabeler();
    }

    @Test
    public void testSidewalk() throws Exception {
        Way osmWay = TraversalPermissionLabelerTest.makeOSMWayFromTags("highway=cycleway;foot=designated;oneway=yes");



        EnumSet<EdgeStore.EdgeFlag> forwardFlags = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardFlags = traversalPermissionLabeler.getPermissions(osmWay, true);

        typeOfEdgeLabeler.label(osmWay, forwardFlags, backwardFlags);

        EnumSet<EdgeStore.EdgeFlag> expectedPermissionsForward = EnumSet.of(EdgeStore.EdgeFlag.SIDEWALK,
            EdgeStore.EdgeFlag.BIKE_PATH, EdgeStore.EdgeFlag.ALLOWS_BIKE, EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
        EnumSet<EdgeStore.EdgeFlag> expectedPermissionsBackward = EnumSet.of(EdgeStore.EdgeFlag.SIDEWALK,  EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);

        assertEquals(expectedPermissionsForward, forwardFlags);
        assertEquals(expectedPermissionsBackward, backwardFlags);

    }
}