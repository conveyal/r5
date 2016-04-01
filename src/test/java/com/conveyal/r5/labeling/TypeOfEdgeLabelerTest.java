package com.conveyal.r5.labeling;

import com.conveyal.osmlib.OSMEntity;
import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.StringJoiner;

import static org.junit.Assert.*;

/**
 * Created by mabu on 27.11.2015.
 */
@RunWith(Parameterized.class)
public class TypeOfEdgeLabelerTest {

    public static final EnumSet<EdgeStore.EdgeFlag> PLATFORM = EnumSet
        .of(EdgeStore.EdgeFlag.PLATFORM, EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR);

    static TypeOfEdgeLabeler typeOfEdgeLabeler;
    static TraversalPermissionLabeler traversalPermissionLabeler;

    @BeforeClass
    public static void setUpClass() {
        typeOfEdgeLabeler = new TypeOfEdgeLabeler();
        traversalPermissionLabeler = new TestPermissionsLabeler();
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
        EnumSet<EdgeStore.EdgeFlag> CAR_CYCLEWAY = EnumSet.copyOf(TraversalPermissionLabelerTest.BICYCLE_AND_CAR);
        CAR_CYCLEWAY.add(EdgeStore.EdgeFlag.BIKE_PATH);
        EnumSet<EdgeStore.EdgeFlag> PEDESTRIAN_BIKE_CYCLEWAY = EnumSet.copyOf(TraversalPermissionLabelerTest.PEDESTRIAN_AND_BICYCLE);
        PEDESTRIAN_BIKE_CYCLEWAY.add(EdgeStore.EdgeFlag.BIKE_PATH);
        return Arrays.asList(new Object[][] {
            {"sidewalk", "highway=cycleway;foot=designated;oneway=yes", EnumSet.of(EdgeStore.EdgeFlag.SIDEWALK,
                EdgeStore.EdgeFlag.BIKE_PATH, EdgeStore.EdgeFlag.ALLOWS_BIKE, EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN,
                EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR),
                EnumSet.of(EdgeStore.EdgeFlag.SIDEWALK,  EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN,
                    EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR)
            },
            {"sidewalk", "highway=footway;footway=sidewalk", EnumSet.of(EdgeStore.EdgeFlag.SIDEWALK,
                EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR), EnumSet.of(EdgeStore.EdgeFlag.SIDEWALK,
                EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR)},
            {"platform", "highway=platform", PLATFORM, PLATFORM},
            {"platform", "public_transport=platform", PLATFORM, PLATFORM},
            {"platform", "railway=platform", PLATFORM, PLATFORM},
            {"monodirectional cycleway", "highway=nobikenoped;cycleway=lane", CAR_CYCLEWAY, CAR_CYCLEWAY},
            {"monodirectional cycleway", "highway=nobikenoped;cycleway=track", CAR_CYCLEWAY, CAR_CYCLEWAY},
            {"directional cycleway", "highway=nobikenoped;cycleway:right=track", CAR_CYCLEWAY, TraversalPermissionLabelerTest.CAR},
            {"directional cycleway", "highway=nobikenoped;cycleway:left=track", TraversalPermissionLabelerTest.CAR, CAR_CYCLEWAY},
            {"directional cycleway", "highway=nobikenoped;cycleway:right=lane", CAR_CYCLEWAY, TraversalPermissionLabelerTest.CAR},
            {"directional cycleway", "highway=nobikenoped;cycleway:left=lane", TraversalPermissionLabelerTest.CAR, CAR_CYCLEWAY},
            {"directional cycleway", "highway=cycleway;oneway=true", PEDESTRIAN_BIKE_CYCLEWAY, TraversalPermissionLabelerTest.PEDESTRIAN},
            {"directional cycleway", "highway=cycleway", PEDESTRIAN_BIKE_CYCLEWAY, PEDESTRIAN_BIKE_CYCLEWAY},
        } );


    }

    @Test
    public void testParam() throws Exception {
        Way osmWay = TraversalPermissionLabelerTest.makeOSMWayFromTags(tags);

        RoadPermission roadPermission = traversalPermissionLabeler.getPermissions(osmWay);


        typeOfEdgeLabeler.label(osmWay, roadPermission.forward, roadPermission.backward);


        assertEquals("Tags: " + tags, expectedForwardFlags, roadPermission.forward);
        assertEquals("Tags: " + tags, expectedBackwardFlags, roadPermission.backward);

    }



}