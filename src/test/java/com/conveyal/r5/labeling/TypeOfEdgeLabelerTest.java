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

    public static final EnumSet<EdgeStore.EdgeFlag> PLATFORM = EnumSet
        .of(EdgeStore.EdgeFlag.PLATFORM);

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

    @Test
    public void testPlatform() throws Exception {
        Way osmWay = TraversalPermissionLabelerTest.makeOSMWayFromTags("highway=platform");

        EnumSet<EdgeStore.EdgeFlag> flag = EnumSet.noneOf(EdgeStore.EdgeFlag.class);

        typeOfEdgeLabeler.label(osmWay, flag, flag);

        assertEquals("highway=platform", PLATFORM, flag);


        osmWay = TraversalPermissionLabelerTest.makeOSMWayFromTags("public_transport=platform");

        flag = EnumSet.noneOf(EdgeStore.EdgeFlag.class);

        typeOfEdgeLabeler.label(osmWay, flag, flag);

        assertEquals("public_transport=platform", PLATFORM, flag);

        osmWay = TraversalPermissionLabelerTest.makeOSMWayFromTags("railway=platform");

        flag = EnumSet.noneOf(EdgeStore.EdgeFlag.class);

        typeOfEdgeLabeler.label(osmWay, flag, flag);

        assertEquals("railway=platform", PLATFORM, flag);
    }

}