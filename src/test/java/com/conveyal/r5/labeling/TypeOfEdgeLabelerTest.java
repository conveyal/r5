package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;

import static org.junit.Assert.*;

/**
 * Created by mabu on 27.11.2015.
 */
@RunWith(Parameterized.class)
public class TypeOfEdgeLabelerTest {

    public static final EnumSet<EdgeStore.EdgeFlag> PLATFORM = EnumSet
        .of(EdgeStore.EdgeFlag.PLATFORM, EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);

    static TypeOfEdgeLabeler typeOfEdgeLabeler;
    static TraversalPermissionLabeler traversalPermissionLabeler;

    @BeforeClass
    public static void setUpClass() {
        typeOfEdgeLabeler = new TypeOfEdgeLabeler();
        traversalPermissionLabeler = new USTraversalPermissionLabeler();
    }

    //name of current test
    private String name;
    //Tags from which way is created
    private String tags;
    private EnumSet<EdgeStore.EdgeFlag> expectedForwardFlags;
    private EnumSet<EdgeStore.EdgeFlag> expectedBackwardFlags;

    public TypeOfEdgeLabelerTest(String name, String tags,
        EnumSet<EdgeStore.EdgeFlag> expectedForwardFlags,
        EnumSet<EdgeStore.EdgeFlag> expectedBackwardFlags) {
        this.name = name;
        this.tags = tags;
        this.expectedForwardFlags = expectedForwardFlags;
        this.expectedBackwardFlags = expectedBackwardFlags;
    }

    @Parameterized.Parameters(name="{0} with tags:{1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
            {"sidewalk", "highway=cycleway;foot=designated;oneway=yes", EnumSet.of(EdgeStore.EdgeFlag.SIDEWALK,
                EdgeStore.EdgeFlag.BIKE_PATH, EdgeStore.EdgeFlag.ALLOWS_BIKE, EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN),
                EnumSet.of(EdgeStore.EdgeFlag.SIDEWALK,  EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN)
            },
            {"sidewalk", "highway=footway;footway=sidewalk", EnumSet.of(EdgeStore.EdgeFlag.SIDEWALK,
                EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN), EnumSet.of(EdgeStore.EdgeFlag.SIDEWALK,
                EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN)},
            {"platform", "highway=platform", PLATFORM, PLATFORM},
            {"platform", "public_transport=platform", PLATFORM, PLATFORM},
            {"platform", "railway=platform", PLATFORM, PLATFORM},
        } );


    }

    @Test
    public void testParam() throws Exception {
        Way osmWay = TraversalPermissionLabelerTest.makeOSMWayFromTags(tags);
        RoadPermission roadPermission = traversalPermissionLabeler.getPermissions(osmWay);


        typeOfEdgeLabeler.label(osmWay, roadPermission.forward, roadPermission.backward);


        assertEquals(expectedForwardFlags, roadPermission.forward);
        assertEquals(expectedBackwardFlags, roadPermission.backward);

    }



}