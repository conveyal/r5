package com.conveyal.r5.labeling;

import com.conveyal.osmlib.OSMEntity;
import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Created by mabu on 26.11.2015.
 */
public class TraversalPermissionLabelerTest {

    static TraversalPermissionLabeler traversalPermissionLabeler;

    public static final EnumSet<EdgeStore.EdgeFlag> ALL = EnumSet
        .of(EdgeStore.EdgeFlag.ALLOWS_BIKE, EdgeStore.EdgeFlag.ALLOWS_CAR,
            EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
    public static final EnumSet<EdgeStore.EdgeFlag> ALLPERMISSIONS = EnumSet
        .of(EdgeStore.EdgeFlag.ALLOWS_BIKE, EdgeStore.EdgeFlag.ALLOWS_CAR,
            EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.NO_THRU_TRAFFIC,
            EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_BIKE, EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_PEDESTRIAN,
            EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR);
    public static final EnumSet<EdgeStore.EdgeFlag> PEDESTRIAN_AND_BICYCLE = EnumSet.of(
        EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_BIKE);
    public static final EnumSet<EdgeStore.EdgeFlag> PEDESTRIAN_AND_CAR = EnumSet.of(
        EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_CAR );
    public static final EnumSet<EdgeStore.EdgeFlag> BICYCLE_AND_CAR = EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_BIKE,
        EdgeStore.EdgeFlag.ALLOWS_CAR);
    public static final EnumSet<EdgeStore.EdgeFlag> NONE = EnumSet.noneOf(EdgeStore.EdgeFlag.class);

    public static final EnumSet<EdgeStore.EdgeFlag> PEDESTRIAN = EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);

    public static final EnumSet<EdgeStore.EdgeFlag> BICYCLE = EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_BIKE);

    public static final EnumSet<EdgeStore.EdgeFlag> CAR = EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_CAR);

    @BeforeClass
    public static void setUpClass() {
        traversalPermissionLabeler = new TestPermissionsLabeler();
    }

    @Test
    public void testCyclewayPermissions() throws Exception {
        Way osmWay = new Way();
        osmWay.addTag("highway", "cycleway");
        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN_AND_BICYCLE, forwardFiltered);
        assertEquals(PEDESTRIAN_AND_BICYCLE, backwardFiltered);


        osmWay.addTag("access", "destination");
        forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        forwardFiltered = filterFlags(forwardPermissions);
        backwardFiltered = filterFlags(backwardPermissions);

        //CAR is destination
        assertEquals(EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_BIKE,
            EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR), forwardFiltered);
        assertEquals(EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_BIKE,
            EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR), backwardFiltered);

    }

    @Test
    public void testOnewayPermissions() {
        Way osmWay = new Way();
        osmWay.addTag("highway", "residential");
        osmWay.addTag("oneway", "true");
        osmWay.addTag("oneway:bicycle", "no");

        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(ALL, forwardFiltered);
        assertEquals(PEDESTRIAN_AND_BICYCLE, backwardFiltered);
    }

    @Test
    public void testPath() throws Exception {
        Way osmWay = makeOSMWayFromTags("highway=path;access=private");

        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        EnumSet<EdgeStore.EdgeFlag> expectedPermissions = EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_BIKE,
            EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.NO_THRU_TRAFFIC_CAR);
        assertEquals(expectedPermissions, forwardFiltered);
        assertEquals(expectedPermissions, backwardFiltered);
    }

    @Test
    public void testPlatform() throws Exception {
        Way osmWay = makeOSMWayFromTags("highway=platform;public_transport=platform");

        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN, forwardFiltered);
        assertEquals(PEDESTRIAN, backwardFiltered);
    }

    @Ignore("specific tagging isn't supported yet in specific permissions")
    @Test
    public void testSidewalk() throws Exception {
        Way osmWay = new Way();
        osmWay.addTag("highway", "footway");
        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN_AND_BICYCLE, forwardFiltered);
        assertEquals(PEDESTRIAN_AND_BICYCLE, backwardFiltered);

        //TODO: this had special permissions in OTP
        osmWay = makeOSMWayFromTags("footway=sidewalk;highway=footway");

        forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        forwardFiltered = filterFlags(forwardPermissions);
        backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN, forwardFiltered);
        assertEquals(PEDESTRIAN, backwardFiltered);
    }

    //TODO: is sidewalk:right onedirectional or bidirectional
    @Ignore("Sidewalks aren't supported yet")
    @Test
    public void testRoadWithSidewalk() {

        Way osmWay = makeOSMWayFromTags("highway=nobikenoped");

        roadFlagComparision(osmWay, CAR, CAR);

        roadFlagComparision(osmWay, "sidewalk", "right", PEDESTRIAN_AND_CAR, CAR);
        roadFlagComparision(osmWay, "sidewalk", "left", CAR, PEDESTRIAN_AND_CAR);
        roadFlagComparision(osmWay, "sidewalk", "both", PEDESTRIAN_AND_CAR, PEDESTRIAN_AND_CAR);
    }


    @Test
    public void testRoadWithBidirectionalCycleway() {

        Way osmWay = makeOSMWayFromTags("highway=nobikenoped");

        roadFlagComparision(osmWay, CAR, CAR);

        roadFlagComparision(osmWay, "cycleway", "lane", BICYCLE_AND_CAR, BICYCLE_AND_CAR);

        roadFlagComparision(osmWay, "cycleway", "track", BICYCLE_AND_CAR, BICYCLE_AND_CAR);

        roadFlagComparision(osmWay, "cycleway:both", "lane", BICYCLE_AND_CAR, BICYCLE_AND_CAR);

        roadFlagComparision(osmWay, "cycleway:both", "track", BICYCLE_AND_CAR, BICYCLE_AND_CAR);

        roadFlagComparision(osmWay, "cycleway", "share_busway", BICYCLE_AND_CAR, BICYCLE_AND_CAR);

        roadFlagComparision(osmWay, "cycleway", "shared_lane", BICYCLE_AND_CAR, BICYCLE_AND_CAR);
    }

    @Test
    public void testRoadWithMonodirectionalCycleway() {
        Way osmWay = makeOSMWayFromTags("highway=nobikenoped");

        roadFlagComparision(osmWay, "cycleway:right", "lane", BICYCLE_AND_CAR, CAR);

        roadFlagComparision(osmWay, "cycleway:right", "track", BICYCLE_AND_CAR, CAR);

        roadFlagComparision(osmWay, "cycleway:left", "lane", CAR, BICYCLE_AND_CAR);

        roadFlagComparision(osmWay, "cycleway:left", "track", CAR, BICYCLE_AND_CAR);

        osmWay = makeOSMWayFromTags("highway=residential;foot=no");

        roadFlagComparision(osmWay, "bicycle:forward", "use_sidepath", CAR, BICYCLE_AND_CAR);

        roadFlagComparision(osmWay, "bicycle:forward", "no", CAR, BICYCLE_AND_CAR);

        roadFlagComparision(osmWay, "bicycle:forward", "dismount", CAR, BICYCLE_AND_CAR);

        roadFlagComparision(osmWay, "bicycle:backward", "use_sidepath", BICYCLE_AND_CAR, CAR);

        roadFlagComparision(osmWay, "bicycle:backward", "no", BICYCLE_AND_CAR, CAR);

        roadFlagComparision(osmWay, "bicycle:backward", "dismount", BICYCLE_AND_CAR, CAR);

        osmWay = makeOSMWayFromTags("cycleway:right=lane;highway=residential;cycleway:left=opposite_lane;oneway=yes");

        roadFlagComparision(osmWay, ALL, PEDESTRIAN_AND_BICYCLE);

        roadFlagComparision(osmWay, "oneway:bicycle", "no", ALL, PEDESTRIAN_AND_BICYCLE);

        osmWay = makeOSMWayFromTags("highway=tertiary;cycleway:left=lane;bicycle:forward=use_sidepath");
        roadFlagComparision(osmWay, PEDESTRIAN_AND_CAR, ALL);

        osmWay = makeOSMWayFromTags("highway=nobikenoped;cycleway:left=lane;bicycle:forward=use_sidepath");
        roadFlagComparision(osmWay, CAR, BICYCLE_AND_CAR);

        osmWay = makeOSMWayFromTags("highway=nobikenoped;foot=yes;oneway=-1;cycleway:left=opposite_lane");
        roadFlagComparision(osmWay, PEDESTRIAN_AND_BICYCLE, PEDESTRIAN_AND_CAR);
    }

    private void roadFlagComparision(Way osmWay, EnumSet<EdgeStore.EdgeFlag> forwardExpected,
        EnumSet<EdgeStore.EdgeFlag> backwardExpected) {
        roadFlagComparision(osmWay, null, null, forwardExpected, backwardExpected);
    }

    /**
     * Makes comparision of way with osmWay tags and newTag with newValue and compares forward and backward permissions with expected permissions
     *
     * Copy of osmWay is made since otherwise tags would be changed
     *
     * @param iosmWay
     * @param newTag
     * @param newValue
     * @param forwardExpected
     * @param backwardExpected
     */
    private static void roadFlagComparision(Way iosmWay, String newTag, String newValue, EnumSet<EdgeStore.EdgeFlag> forwardExpected, EnumSet<EdgeStore.EdgeFlag> backwardExpected) {
        Way osmWay = new Way();

        StringJoiner stringJoiner = new StringJoiner(";");

        for (OSMEntity.Tag tag: iosmWay.tags) {
            osmWay.addTag(tag.key, tag.value);
            stringJoiner.add(tag.key+"="+tag.value);
        }
        if (newTag != null && newValue != null) {
            osmWay.addTag(newTag, newValue);
            stringJoiner.add(newTag+"="+newValue);
        }
        Set<EdgeStore.EdgeFlag> forwardFiltered;
        Set<EdgeStore.EdgeFlag> backwardFiltered;

        RoadPermission roadPermission = traversalPermissionLabeler.getPermissions(osmWay);

        forwardFiltered = filterFlags(roadPermission.forward);
        backwardFiltered = filterFlags(roadPermission.backward);

        String tags = "Tags: " + stringJoiner.toString();

        assertEquals(tags, forwardExpected, forwardFiltered);
        assertEquals(tags, backwardExpected, backwardFiltered);
    }

    @Test
    public void testSteps() throws Exception {
        Way osmWay = makeOSMWayFromTags("highway=steps");
        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN, forwardFiltered);
        assertEquals(PEDESTRIAN, backwardFiltered);
    }

    @Test
    public void testSidepath() throws Exception {
        Way osmWay = makeOSMWayFromTags("highway=tertiary;bicycle=use_sidepath");

        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN_AND_CAR, forwardFiltered);
        assertEquals(PEDESTRIAN_AND_CAR, backwardFiltered);
    }

    @Test
    public void testSpecificPermission() throws Exception {
        Way osmWay = makeOSMWayFromTags("highway=primary;bicycle=use_sidepath;foot=no;junction=roundabout");

        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(CAR, forwardFiltered);
        assertEquals(NONE, backwardFiltered);
    }

    /**
     * Removes all flags except permissions
     * @param permissions
     * @return
     */
    private static Set<EdgeStore.EdgeFlag> filterFlags(EnumSet<EdgeStore.EdgeFlag> permissions) {
        return permissions.stream()
            .filter(ALLPERMISSIONS::contains)
            .collect(Collectors.toSet());
    }

    /**
     * Creates osmway based on provided tags
     *
     * For example: footway=sidewalk;highway=footway
     * This adds two tags footway=sidewalk and highway=footway. Order doesn't matter.
     * @param tags with tags separated with ; and tag and value separated with =
     * @return
     */
    protected static Way makeOSMWayFromTags(String tags) {
        Way osmWay = new Way();
        String[] pairs = tags.split(";");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            osmWay.addTag(kv[0], kv[1]);
        }
        return osmWay;
    }
}